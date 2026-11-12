"""P37: accounts for AB File Pro.

- Sign in with Google (the server checks Google's ID token itself; it never trusts the phone's word).
- Points live on the server for signed-in people, so they follow the person to any device.
- Pro can be granted by email from the admin page (gifts, testers, people paid outside Google Play).
- The person can delete the account (Google Play requires that for apps with sign-in).

Nothing here touches the family-sync endpoints.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import time
import urllib.request
from typing import Any, Callable

import jwt
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

router = APIRouter()

# ---- the same rules the app used when points lived only on the phone (PointsPolicy.kt) ----
START_BALANCE = 20
DAILY_BONUS = 2
AD_REWARD = 5
MAX_ADS_PER_DAY = 3
MAX_BALANCE = 100
MAX_SPEND = 20
DAY_MS = 86_400_000
EGYPT_OFFSET_SECONDS = 2 * 3600  # "a day" for the daily gift follows Egypt standard time

_deps: dict[str, Callable[..., Any]] = {}


def bind(db: Callable[..., Any], check_rate: Callable[..., None], client_key: Callable[..., str]) -> None:
    """main.py hands over its database and rate-limit helpers (avoids a circular import)."""
    _deps.update(db=db, check_rate=check_rate, client_key=client_key)


def _db():
    return _deps["db"]()


def _rate(request: Request, bucket: str, limit: int) -> None:
    _deps["check_rate"](f"{bucket}:{_deps['client_key'](request)}", limit)


def google_client_ids() -> set[str]:
    raw = os.getenv("GOOGLE_CLIENT_IDS") or os.getenv("GOOGLE_CLIENT_ID") or ""
    return {part.strip() for part in raw.split(",") if part.strip()}


def admin_token() -> str:
    return os.getenv("ADMIN_TOKEN", "").strip()


def today() -> int:
    return int((time.time() + EGYPT_OFFSET_SECONDS) // 86400)


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------- Google ID token ----------

_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
_jwks_cache: dict[str, Any] = {"at": 0.0, "keys": []}


def _fetch_google_keys() -> list[dict[str, Any]]:
    if time.time() - _jwks_cache["at"] < 3600 and _jwks_cache["keys"]:
        return _jwks_cache["keys"]
    with urllib.request.urlopen(_JWKS_URL, timeout=8) as resp:  # noqa: S310 - fixed https URL
        keys = json.loads(resp.read().decode())["keys"]
    _jwks_cache.update(at=time.time(), keys=keys)
    return keys


# Tests replace this with a function returning their own key set.
jwks_provider: Callable[[], list[dict[str, Any]]] = _fetch_google_keys


def verify_google_id_token(token: str) -> dict[str, Any]:
    ids = google_client_ids()
    if not ids:
        raise HTTPException(status_code=503, detail="google sign-in is not configured")
    try:
        header = jwt.get_unverified_header(token)
        if header.get("alg") != "RS256" or not header.get("kid"):
            raise ValueError("bad header")
        jwk = next((k for k in jwks_provider() if k.get("kid") == header["kid"]), None)
        if jwk is None:
            raise ValueError("unknown key")
        payload = jwt.decode(
            token,
            jwt.PyJWK(jwk).key,
            algorithms=["RS256"],
            audience=list(ids),
            issuer=["accounts.google.com", "https://accounts.google.com"],
            options={"require": ["exp", "sub", "iss", "aud"]},
            leeway=30,
        )
    except HTTPException:
        raise
    except Exception:  # noqa: BLE001 - every failure is simply "not a valid Google token"
        raise HTTPException(status_code=401, detail="invalid google token") from None
    email = str(payload.get("email", "")).strip().lower()
    if not email or payload.get("email_verified") not in (True, "true"):
        raise HTTPException(status_code=401, detail="google account has no verified email")
    return {"sub": str(payload["sub"]), "email": email, "name": str(payload.get("name", ""))[:120]}


# ---------------------------------------------------------------- schema ----------

def init_schema(conn) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS accounts (
            id BIGSERIAL PRIMARY KEY,
            google_sub VARCHAR(64) NOT NULL UNIQUE,
            email VARCHAR(254) NOT NULL,
            name VARCHAR(120) NOT NULL DEFAULT '',
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            balance INTEGER NOT NULL DEFAULT 20,
            last_bonus_day BIGINT NOT NULL DEFAULT -1,
            ads_day BIGINT NOT NULL DEFAULT -1,
            ads_today INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS accounts_email_idx ON accounts (email)")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS account_sessions (
            token_hash CHAR(64) PRIMARY KEY,
            account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            device_id VARCHAR(100) NOT NULL DEFAULT '',
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS points_ledger (
            account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            idem_key VARCHAR(80) NOT NULL,
            delta INTEGER NOT NULL,
            reason VARCHAR(20) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (account_id, idem_key)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS pro_grants (
            email VARCHAR(254) PRIMARY KEY,
            plan VARCHAR(20) NOT NULL,
            ends_at BIGINT,
            note VARCHAR(200) NOT NULL DEFAULT '',
            granted_at BIGINT NOT NULL,
            revoked_at BIGINT
        )
        """
    )


# ---------------------------------------------------------------- helpers ----------

def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _bearer(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing token")
    return authorization[7:].strip()


def _cap(balance: int, value: int) -> int:
    """A gift never takes points away: a balance already at the cap just stops growing."""
    return balance if balance >= MAX_BALANCE else min(value, MAX_BALANCE)


def pro_state(conn, email: str) -> dict[str, Any]:
    row = conn.execute(
        "SELECT plan, ends_at FROM pro_grants WHERE email = %s AND revoked_at IS NULL "
        "AND (ends_at IS NULL OR ends_at > %s)",
        (email.lower(), now_ms()),
    ).fetchone()
    if row is None:
        return {"active": False, "plan": None, "endsAt": None, "source": None}
    return {"active": True, "plan": row[0], "endsAt": row[1], "source": "admin"}


def snapshot(conn, account_id: int) -> dict[str, Any]:
    a = conn.execute(
        "SELECT email, name, balance, last_bonus_day, ads_day, ads_today FROM accounts WHERE id = %s",
        (account_id,),
    ).fetchone()
    day = today()
    ads_left = MAX_ADS_PER_DAY - a[5] if a[4] == day else MAX_ADS_PER_DAY
    return {
        "email": a[0],
        "name": a[1],
        "points": {
            "balance": a[2],
            "adsLeftToday": max(ads_left, 0),
            "dailyBonusAvailable": a[3] != day,
        },
        "pro": pro_state(conn, a[0]),
        "serverTime": now_ms(),
    }


def _account_id(conn, authorization: str | None) -> int:
    row = conn.execute(
        "UPDATE account_sessions SET last_seen_at = NOW() WHERE token_hash = %s RETURNING account_id",
        (_hash(_bearer(authorization)),),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=401, detail="session expired, sign in again")
    return row[0]


def _locked(conn, account_id: int):
    return conn.execute(
        "SELECT balance, last_bonus_day, ads_day, ads_today, email FROM accounts WHERE id = %s FOR UPDATE",
        (account_id,),
    ).fetchone()


def _claim_key(conn, account_id: int, key: str, delta: int, reason: str) -> bool:
    """True the first time a key is used for this account; a retried request is a harmless repeat."""
    row = conn.execute(
        "INSERT INTO points_ledger (account_id, idem_key, delta, reason) VALUES (%s, %s, %s, %s) "
        "ON CONFLICT DO NOTHING RETURNING 1",
        (account_id, key, delta, reason),
    ).fetchone()
    return row is not None


# ---------------------------------------------------------------- app endpoints ----------

class GoogleSignIn(BaseModel):
    idToken: str = Field(min_length=20, max_length=4096)
    deviceId: str = Field(default="", max_length=100)
    # Points the phone already had before accounts existed; used only when the account is brand new.
    localBalance: int | None = Field(default=None, ge=0, le=100_000)


class PointsRequest(BaseModel):
    key: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_.:-]+$")
    amount: int = Field(default=0, ge=0, le=MAX_SPEND)


@router.post("/v1/auth/google")
def sign_in_with_google(body: GoogleSignIn, request: Request) -> dict[str, Any]:
    _rate(request, "signin", 20)
    info = verify_google_id_token(body.idToken)
    with _db() as conn:
        row = conn.execute("SELECT id FROM accounts WHERE google_sub = %s", (info["sub"],)).fetchone()
        if row is None:
            start = START_BALANCE if body.localBalance is None else min(body.localBalance, MAX_BALANCE)
            row = conn.execute(
                "INSERT INTO accounts (google_sub, email, name, balance, last_bonus_day) "
                "VALUES (%s, %s, %s, %s, %s) RETURNING id",
                (info["sub"], info["email"], info["name"], start, today()),
            ).fetchone()
            created = True
        else:
            conn.execute("UPDATE accounts SET email = %s, name = %s WHERE id = %s", (info["email"], info["name"], row[0]))
            created = False
        token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO account_sessions (token_hash, account_id, device_id) VALUES (%s, %s, %s)",
            (_hash(token), row[0], body.deviceId),
        )
        return {"token": token, "created": created, "account": snapshot(conn, row[0])}


@router.get("/v1/account")
def get_account(request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _rate(request, "account", 120)
    with _db() as conn:
        return snapshot(conn, _account_id(conn, authorization))


@router.post("/v1/account/sign-out")
def sign_out(authorization: str | None = Header(default=None)) -> dict[str, bool]:
    with _db() as conn:
        conn.execute("DELETE FROM account_sessions WHERE token_hash = %s", (_hash(_bearer(authorization)),))
    return {"signedOut": True}


@router.delete("/v1/account")
def delete_account(request: Request, authorization: str | None = Header(default=None)) -> dict[str, bool]:
    _rate(request, "account-delete", 10)
    with _db() as conn:
        account_id = _account_id(conn, authorization)
        conn.execute("DELETE FROM accounts WHERE id = %s", (account_id,))  # sessions and ledger cascade
    return {"deleted": True}


@router.post("/v1/account/points/spend")
def spend_points(body: PointsRequest, request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _rate(request, "points", 120)
    if body.amount < 1:
        raise HTTPException(status_code=400, detail="amount must be at least 1")
    with _db() as conn:
        account_id = _account_id(conn, authorization)
        balance, _, _, _, email = _locked(conn, account_id)
        if pro_state(conn, email)["active"]:
            return {"applied": False, "proActive": True, "balance": balance}  # Pro never pays points
        if not _claim_key(conn, account_id, body.key, -body.amount, "spend"):
            return {"applied": False, "proActive": False, "balance": balance}
        if body.amount > balance:
            conn.rollback()
            raise HTTPException(status_code=402, detail={"error": "not enough points", "balance": balance})
        conn.execute("UPDATE accounts SET balance = balance - %s WHERE id = %s", (body.amount, account_id))
        return {"applied": True, "proActive": False, "balance": balance - body.amount}


@router.post("/v1/account/points/daily-bonus")
def daily_bonus(request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    _rate(request, "points", 120)
    with _db() as conn:
        account_id = _account_id(conn, authorization)
        balance, last_bonus_day, _, _, _ = _locked(conn, account_id)
        day = today()
        if last_bonus_day == day:
            return {"applied": False, "balance": balance}
        new_balance = _cap(balance, balance + DAILY_BONUS)
        conn.execute("UPDATE accounts SET balance = %s, last_bonus_day = %s WHERE id = %s", (new_balance, day, account_id))
        return {"applied": True, "balance": new_balance}


@router.post("/v1/account/points/earn-ad")
def earn_from_ad(body: PointsRequest, request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    """A finished rewarded video. The server enforces the daily limit; the video itself is Google's."""
    _rate(request, "points", 120)
    with _db() as conn:
        account_id = _account_id(conn, authorization)
        balance, _, ads_day, ads_today, _ = _locked(conn, account_id)
        day = today()
        used = ads_today if ads_day == day else 0
        if used >= MAX_ADS_PER_DAY:
            return {"applied": False, "balance": balance, "adsLeftToday": 0}
        if not _claim_key(conn, account_id, body.key, AD_REWARD, "ad"):
            return {"applied": False, "balance": balance, "adsLeftToday": MAX_ADS_PER_DAY - used}
        new_balance = _cap(balance, balance + AD_REWARD)
        conn.execute(
            "UPDATE accounts SET balance = %s, ads_day = %s, ads_today = %s WHERE id = %s",
            (new_balance, day, used + 1, account_id),
        )
        return {"applied": True, "balance": new_balance, "adsLeftToday": MAX_ADS_PER_DAY - used - 1}


# ---------------------------------------------------------------- admin ----------

EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,189}\.[^@\s]{2,}$")
PLAN_DAYS = {"monthly": 30, "yearly": 365, "lifetime": None}


def _require_admin(request: Request, token: str | None) -> None:
    configured = admin_token()
    if len(configured) < 16:
        raise HTTPException(status_code=503, detail="admin is not configured")
    _rate(request, "admin", 60)
    if not token or not hmac.compare_digest(token.encode(), configured.encode()):
        raise HTTPException(status_code=401, detail="wrong admin token")


def _clean_email(value: str) -> str:
    email = value.strip().lower()
    if not EMAIL_RE.match(email):
        raise HTTPException(status_code=400, detail="invalid email")
    return email


class GrantRequest(BaseModel):
    email: str = Field(max_length=254)
    plan: str = Field(pattern=r"^(monthly|yearly|lifetime|days)$")
    days: int | None = Field(default=None, ge=1, le=3650)
    note: str = Field(default="", max_length=200)


class AdminPoints(BaseModel):
    email: str = Field(max_length=254)
    delta: int = Field(ge=-100, le=100)


@router.post("/v1/admin/grants")
def admin_grant(body: GrantRequest, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    email = _clean_email(body.email)
    days = body.days if body.plan == "days" else PLAN_DAYS[body.plan]
    if body.plan == "days" and days is None:
        raise HTTPException(status_code=400, detail="days is required for plan 'days'")
    now = now_ms()
    with _db() as conn:
        current = conn.execute(
            "SELECT ends_at, revoked_at FROM pro_grants WHERE email = %s", (email,)
        ).fetchone()
        if days is None:
            ends = None  # lifetime
        else:
            # Extending a grant that is still running adds time after it instead of losing the rest.
            base = now
            if current and current[1] is None and current[0] is not None and current[0] > now:
                base = current[0]
            ends = base + days * DAY_MS
        conn.execute(
            """
            INSERT INTO pro_grants (email, plan, ends_at, note, granted_at, revoked_at)
            VALUES (%s, %s, %s, %s, %s, NULL)
            ON CONFLICT (email) DO UPDATE SET plan = EXCLUDED.plan, ends_at = EXCLUDED.ends_at,
                note = EXCLUDED.note, granted_at = EXCLUDED.granted_at, revoked_at = NULL
            """,
            (email, body.plan, ends, body.note.strip(), now),
        )
        return {"email": email, "plan": body.plan, "endsAt": ends}


@router.delete("/v1/admin/grants/{email}")
def admin_revoke(email: str, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    with _db() as conn:
        row = conn.execute(
            "UPDATE pro_grants SET revoked_at = %s WHERE email = %s AND revoked_at IS NULL RETURNING 1",
            (now_ms(), _clean_email(email)),
        ).fetchone()
    return {"revoked": row is not None}


@router.get("/v1/admin/grants")
def admin_list_grants(request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    with _db() as conn:
        rows = conn.execute(
            "SELECT email, plan, ends_at, note, granted_at, revoked_at FROM pro_grants ORDER BY granted_at DESC LIMIT 200"
        ).fetchall()
    now = now_ms()
    return {
        "grants": [
            {
                "email": r[0], "plan": r[1], "endsAt": r[2], "note": r[3], "grantedAt": r[4],
                "active": r[5] is None and (r[2] is None or r[2] > now),
            }
            for r in rows
        ]
    }


@router.get("/v1/admin/accounts")
def admin_list_accounts(request: Request, q: str = "", x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    like = "%" + q.strip().lower().replace("%", "").replace("_", "")[:100] + "%"
    with _db() as conn:
        rows = conn.execute(
            "SELECT id, email, name, balance, created_at FROM accounts WHERE lower(email) LIKE %s "
            "ORDER BY created_at DESC LIMIT 50",
            (like,),
        ).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
        accounts = [
            {"email": r[1], "name": r[2], "balance": r[3], "createdAt": r[4].isoformat(), "pro": pro_state(conn, r[1])["active"]}
            for r in rows
        ]
    return {"total": total, "accounts": accounts}


@router.post("/v1/admin/points")
def admin_adjust_points(body: AdminPoints, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    email = _clean_email(body.email)
    with _db() as conn:
        row = conn.execute("SELECT id, balance FROM accounts WHERE email = %s ORDER BY id LIMIT 1 FOR UPDATE", (email,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="no account with this email")
        new_balance = max(0, min(row[1] + body.delta, MAX_BALANCE))
        conn.execute("UPDATE accounts SET balance = %s WHERE id = %s", (new_balance, row[0]))
        return {"email": email, "balance": new_balance}


ADMIN_PAGE = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>AB File Pro - الإدارة</title>
<style>
:root{--bg:#f6f7fb;--card:#fff;--ink:#16213e;--mut:#667;--line:#dde1ec;--pri:#2563eb;--bad:#c0392b;--ok:#1b7f4b}
@media (prefers-color-scheme:dark){:root{--bg:#101418;--card:#1a2027;--ink:#e8ecf3;--mut:#9aa6b8;--line:#2b3440;--pri:#6ea0ff;--bad:#ff8a7a;--ok:#5fd39a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,"Segoe UI",Tahoma,sans-serif;padding:16px}
main{max-width:560px;margin:0 auto;display:grid;gap:14px}
h1{font-size:20px;margin:4px 0}h2{font-size:16px;margin:0 0 8px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:14px}
label{display:block;font-size:13px;color:var(--mut);margin:8px 0 4px}
input,select,button{width:100%;font:inherit;padding:11px 12px;border-radius:12px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
button{background:var(--pri);color:#fff;border:0;font-weight:700;margin-top:10px}
button.alt{background:transparent;color:var(--bad);border:1px solid var(--bad)}
.msg{min-height:22px;font-size:14px;margin-top:8px}.ok{color:var(--ok)}.bad{color:var(--bad)}
.row{display:flex;justify-content:space-between;gap:8px;padding:8px 0;border-top:1px solid var(--line);font-size:14px;word-break:break-all}
.mut{color:var(--mut);font-size:12px}
</style></head><body><main>
<h1>AB File Pro — الإدارة</h1>
<section class="card"><h2>الدخول</h2>
<label for="tok">رمز الإدارة</label><input id="tok" type="password" autocomplete="off">
<div class="mut">بيتحفظ في الجلسة دي بس ويتمسح لما تقفل الصفحة.</div></section>
<section class="card"><h2>تفعيل Pro بالإيميل</h2>
<label for="ge">الإيميل</label><input id="ge" type="email" inputmode="email" autocomplete="off">
<label for="gp">الباقة</label><select id="gp"><option value="monthly">شهري (٣٠ يوم)</option><option value="yearly">سنوي (٣٦٥ يوم)</option><option value="lifetime">مدى الحياة</option><option value="days">عدد أيام محدد</option></select>
<label for="gd">عدد الأيام (للباقة المحددة)</label><input id="gd" type="number" min="1" max="3650" value="7">
<label for="gn">ملاحظة</label><input id="gn" maxlength="200">
<button id="bg">فعّل</button><button id="br" class="alt">إلغاء التفعيل</button><div id="mg" class="msg"></div></section>
<section class="card"><h2>تعديل النقاط</h2>
<label for="pe">الإيميل</label><input id="pe" type="email" inputmode="email" autocomplete="off">
<label for="pd">الزيادة أو النقص (مثال 10 أو -5)</label><input id="pd" type="number" min="-100" max="100" value="10">
<button id="bp">طبّق</button><div id="mp" class="msg"></div></section>
<section class="card"><h2>الحسابات</h2>
<label for="qs">بحث بالإيميل</label><input id="qs" autocomplete="off"><button id="bq">اعرض</button>
<div id="mq" class="msg"></div><div id="aq"></div></section>
<section class="card"><h2>التفعيلات</h2><button id="bl">اعرض التفعيلات</button><div id="ml" class="msg"></div><div id="al"></div></section>
</main><script>
const $=id=>document.getElementById(id);
const tok=$("tok");try{tok.value=sessionStorage.getItem("abAdmin")||""}catch(e){}
tok.addEventListener("input",()=>{try{sessionStorage.setItem("abAdmin",tok.value)}catch(e){}});
async function call(method,path,body){
  const r=await fetch(path,{method,headers:{"X-Admin-Token":tok.value,"Content-Type":"application/json"},body:body?JSON.stringify(body):undefined});
  let d={};try{d=await r.json()}catch(e){}
  if(!r.ok)throw new Error(typeof d.detail==="string"?d.detail:("خطأ "+r.status));
  return d;
}
function say(id,t,ok){const e=$(id);e.textContent=t;e.className="msg "+(ok?"ok":"bad")}
function line(parent,a,b){const d=document.createElement("div");d.className="row";const x=document.createElement("span");x.textContent=a;const y=document.createElement("span");y.className="mut";y.textContent=b;d.append(x,y);parent.append(d)}
const fmt=ms=>ms==null?"مدى الحياة":new Date(ms).toLocaleDateString("ar-EG");
$("bg").onclick=async()=>{try{const p=$("gp").value;const d=await call("POST","/v1/admin/grants",{email:$("ge").value,plan:p,days:p==="days"?Number($("gd").value):null,note:$("gn").value});say("mg","تم: "+d.email+" — لحد "+fmt(d.endsAt),true)}catch(e){say("mg",e.message,false)}};
$("br").onclick=async()=>{try{const d=await call("DELETE","/v1/admin/grants/"+encodeURIComponent($("ge").value.trim()));say("mg",d.revoked?"اتلغى التفعيل":"مفيش تفعيل شغال للإيميل ده",d.revoked)}catch(e){say("mg",e.message,false)}};
$("bp").onclick=async()=>{try{const d=await call("POST","/v1/admin/points",{email:$("pe").value,delta:Number($("pd").value)});say("mp","الرصيد دلوقتي "+d.balance,true)}catch(e){say("mp",e.message,false)}};
$("bq").onclick=async()=>{try{const d=await call("GET","/v1/admin/accounts?q="+encodeURIComponent($("qs").value));const box=$("aq");box.textContent="";say("mq","إجمالي الحسابات: "+d.total,true);d.accounts.forEach(a=>line(box,a.email+(a.pro?" ★":""),a.balance+" نقطة"))}catch(e){say("mq",e.message,false)}};
$("bl").onclick=async()=>{try{const d=await call("GET","/v1/admin/grants");const box=$("al");box.textContent="";say("ml",d.grants.length+" تفعيل",true);d.grants.forEach(g=>line(box,g.email,(g.active?"شغال":"منتهي/ملغي")+" — "+fmt(g.endsAt)))}catch(e){say("ml",e.message,false)}};
</script></body></html>"""


@router.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_page() -> str:
    return ADMIN_PAGE
