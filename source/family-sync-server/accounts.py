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

# Editable from the admin site; these are the defaults and the allowed range of each rule.
RULE_LIMITS: dict[str, tuple[int, int, int]] = {
    # key: (default, min, max)
    "start_balance": (START_BALANCE, 0, 500),
    "daily_bonus": (DAILY_BONUS, 0, 50),
    "ad_reward": (AD_REWARD, 0, 100),
    "max_ads_per_day": (MAX_ADS_PER_DAY, 0, 20),
    "max_balance": (MAX_BALANCE, 10, 1000),
    "cost_per_page": (1, 0, 20),
    "cost_translate": (2, 0, 50),
    "max_scan_cost": (MAX_SPEND, 1, 100),
}

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
    conn.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS app_settings (key VARCHAR(40) PRIMARY KEY, value INTEGER NOT NULL)"
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


def get_rules(conn) -> dict[str, int]:
    rules = {key: limits[0] for key, limits in RULE_LIMITS.items()}
    for key, value in conn.execute("SELECT key, value FROM app_settings").fetchall():
        if key in rules:
            rules[key] = value
    return rules


def _cap(balance: int, value: int, cap: int = MAX_BALANCE) -> int:
    """A gift never takes points away: a balance already at the cap just stops growing."""
    return balance if balance >= cap else min(value, cap)


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
    rules = get_rules(conn)
    ads_left = rules["max_ads_per_day"] - a[5] if a[4] == day else rules["max_ads_per_day"]
    return {
        "email": a[0],
        "name": a[1],
        "points": {
            "balance": a[2],
            "adsLeftToday": max(ads_left, 0),
            "dailyBonusAvailable": a[3] != day,
        },
        "pro": pro_state(conn, a[0]),
        "rules": rules,
        "serverTime": now_ms(),
    }


def _account_id(conn, authorization: str | None) -> int:
    row = conn.execute(
        "UPDATE account_sessions s SET last_seen_at = NOW() FROM accounts a "
        "WHERE s.token_hash = %s AND a.id = s.account_id RETURNING s.account_id, a.suspended_at",
        (_hash(_bearer(authorization)),),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=401, detail="session expired, sign in again")
    if row[1] is not None:
        raise HTTPException(status_code=403, detail="account suspended")
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
    amount: int = Field(default=0, ge=0, le=100)


@router.post("/v1/auth/google")
def sign_in_with_google(body: GoogleSignIn, request: Request) -> dict[str, Any]:
    _rate(request, "signin", 20)
    info = verify_google_id_token(body.idToken)
    with _db() as conn:
        rules = get_rules(conn)
        row = conn.execute("SELECT id, suspended_at FROM accounts WHERE google_sub = %s", (info["sub"],)).fetchone()
        if row is not None and row[1] is not None:
            raise HTTPException(status_code=403, detail="account suspended")
        if row is None:
            start = rules["start_balance"] if body.localBalance is None else min(body.localBalance, rules["max_balance"])
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


@router.get("/v1/rules")
def public_rules() -> dict[str, Any]:
    """The points rules the app should follow (the admin site can change them)."""
    with _db() as conn:
        return {"rules": get_rules(conn)}


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
        rules = get_rules(conn)
        if body.amount > max(rules["max_scan_cost"], rules["cost_translate"]):
            raise HTTPException(status_code=400, detail="amount is above the allowed cost")
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
        rules = get_rules(conn)
        balance, last_bonus_day, _, _, _ = _locked(conn, account_id)
        day = today()
        if last_bonus_day == day:
            return {"applied": False, "balance": balance}
        new_balance = _cap(balance, balance + rules["daily_bonus"], rules["max_balance"])
        conn.execute("UPDATE accounts SET balance = %s, last_bonus_day = %s WHERE id = %s", (new_balance, day, account_id))
        return {"applied": True, "balance": new_balance}


@router.post("/v1/account/points/earn-ad")
def earn_from_ad(body: PointsRequest, request: Request, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    """A finished rewarded video. The server enforces the daily limit; the video itself is Google's."""
    _rate(request, "points", 120)
    with _db() as conn:
        account_id = _account_id(conn, authorization)
        rules = get_rules(conn)
        balance, _, ads_day, ads_today, _ = _locked(conn, account_id)
        day = today()
        used = ads_today if ads_day == day else 0
        limit = rules["max_ads_per_day"]
        if used >= limit:
            return {"applied": False, "balance": balance, "adsLeftToday": 0}
        if not _claim_key(conn, account_id, body.key, rules["ad_reward"], "ad"):
            return {"applied": False, "balance": balance, "adsLeftToday": limit - used}
        new_balance = _cap(balance, balance + rules["ad_reward"], rules["max_balance"])
        conn.execute(
            "UPDATE accounts SET balance = %s, ads_day = %s, ads_today = %s WHERE id = %s",
            (new_balance, day, used + 1, account_id),
        )
        return {"applied": True, "balance": new_balance, "adsLeftToday": limit - used - 1}


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
def admin_list_grants(
    request: Request, q: str = "", status: str = "all", x_admin_token: str | None = Header(default=None)
) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    like = "%" + _like_text(q) + "%"
    with _db() as conn:
        rows = conn.execute(
            "SELECT email, plan, ends_at, note, granted_at, revoked_at FROM pro_grants "
            "WHERE email LIKE %s ORDER BY granted_at DESC LIMIT 300",
            (like,),
        ).fetchall()
    now = now_ms()
    grants = [
        {
            "email": r[0], "plan": r[1], "endsAt": r[2], "note": r[3], "grantedAt": r[4],
            "active": r[5] is None and (r[2] is None or r[2] > now),
            "expiringSoon": r[5] is None and r[2] is not None and now < r[2] < now + 7 * DAY_MS,
        }
        for r in rows
    ]
    if status == "active":
        grants = [g for g in grants if g["active"]]
    elif status == "ended":
        grants = [g for g in grants if not g["active"]]
    elif status == "expiring":
        grants = [g for g in grants if g["expiringSoon"]]
    return {"grants": grants}


def _like_text(q: str) -> str:
    return q.strip().lower().replace("%", "").replace("_", "").replace("\\", "")[:100]


@router.get("/v1/admin/accounts")
def admin_list_accounts(
    request: Request,
    q: str = "",
    filter: str = "all",
    offset: int = 0,
    x_admin_token: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    where = ["lower(a.email) LIKE %s"]
    params: list[Any] = ["%" + _like_text(q) + "%"]
    pro_sql = (
        "EXISTS (SELECT 1 FROM pro_grants g WHERE g.email = lower(a.email) AND g.revoked_at IS NULL "
        "AND (g.ends_at IS NULL OR g.ends_at > %s))"
    )
    if filter == "pro":
        where.append(pro_sql)
        params.append(now_ms())
    elif filter == "free":
        where.append("NOT " + pro_sql)
        params.append(now_ms())
    elif filter == "suspended":
        where.append("a.suspended_at IS NOT NULL")
    clause = " AND ".join(where)
    with _db() as conn:
        total = conn.execute(f"SELECT COUNT(*) FROM accounts a WHERE {clause}", params).fetchone()[0]
        rows = conn.execute(
            f"SELECT a.email, a.name, a.balance, a.created_at, a.suspended_at, {pro_sql} FROM accounts a "
            f"WHERE {clause} ORDER BY a.created_at DESC LIMIT 30 OFFSET %s",
            [now_ms(), *params, max(offset, 0)],
        ).fetchall()
    return {
        "total": total,
        "accounts": [
            {"email": r[0], "name": r[1], "balance": r[2], "createdAt": r[3].isoformat(),
             "suspended": r[4] is not None, "pro": bool(r[5])}
            for r in rows
        ],
    }


@router.get("/v1/admin/account")
def admin_account_detail(request: Request, email: str, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    email = _clean_email(email)
    with _db() as conn:
        row = conn.execute("SELECT id, created_at, suspended_at FROM accounts WHERE email = %s ORDER BY id LIMIT 1", (email,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="no account with this email")
        snap = snapshot(conn, row[0])
        ledger = conn.execute(
            "SELECT delta, reason, created_at FROM points_ledger WHERE account_id = %s ORDER BY created_at DESC LIMIT 30",
            (row[0],),
        ).fetchall()
        sessions = conn.execute("SELECT COUNT(*) FROM account_sessions WHERE account_id = %s", (row[0],)).fetchone()[0]
        grant = conn.execute(
            "SELECT plan, ends_at, note, revoked_at FROM pro_grants WHERE email = %s", (email,)
        ).fetchone()
    return {
        **snap,
        "createdAt": row[1].isoformat(),
        "suspended": row[2] is not None,
        "sessions": sessions,
        "grant": None if grant is None else {"plan": grant[0], "endsAt": grant[1], "note": grant[2], "revoked": grant[3] is not None},
        "ledger": [{"delta": d, "reason": r, "at": c.isoformat()} for d, r, c in ledger],
    }


class SuspendRequest(BaseModel):
    email: str = Field(max_length=254)
    suspended: bool


@router.post("/v1/admin/accounts/suspend")
def admin_suspend(body: SuspendRequest, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    email = _clean_email(body.email)
    with _db() as conn:
        row = conn.execute(
            "UPDATE accounts SET suspended_at = CASE WHEN %s THEN NOW() ELSE NULL END WHERE email = %s RETURNING id",
            (body.suspended, email),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="no account with this email")
        if body.suspended:
            conn.execute("DELETE FROM account_sessions WHERE account_id = %s", (row[0],))
    return {"email": email, "suspended": body.suspended}


@router.delete("/v1/admin/account")
def admin_delete_account(request: Request, email: str, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    with _db() as conn:
        row = conn.execute("DELETE FROM accounts WHERE email = %s RETURNING 1", (_clean_email(email),)).fetchone()
    return {"deleted": row is not None}


@router.post("/v1/admin/points")
def admin_adjust_points(body: AdminPoints, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    email = _clean_email(body.email)
    with _db() as conn:
        cap = get_rules(conn)["max_balance"]
        row = conn.execute("SELECT id, balance FROM accounts WHERE email = %s ORDER BY id LIMIT 1 FOR UPDATE", (email,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="no account with this email")
        new_balance = max(0, min(row[1] + body.delta, max(cap, row[1])))
        conn.execute("UPDATE accounts SET balance = %s WHERE id = %s", (new_balance, row[0]))
        conn.execute(
            "INSERT INTO points_ledger (account_id, idem_key, delta, reason) VALUES (%s, %s, %s, 'admin')",
            (row[0], "admin-" + secrets.token_hex(8), new_balance - row[1]),
        )
        return {"email": email, "balance": new_balance}


class GiftAll(BaseModel):
    delta: int = Field(ge=1, le=50)


@router.post("/v1/admin/points/gift-all")
def admin_gift_all(body: GiftAll, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    """A gift to every account. Like every gift it never pushes a balance above the cap."""
    _require_admin(request, x_admin_token)
    with _db() as conn:
        cap = get_rules(conn)["max_balance"]
        rows = conn.execute(
            "UPDATE accounts SET balance = LEAST(balance + %s, %s) WHERE balance < %s RETURNING 1",
            (body.delta, cap, cap),
        ).fetchall()
    return {"accountsUpdated": len(rows)}


class RulesUpdate(BaseModel):
    rules: dict[str, int]


@router.get("/v1/admin/rules")
def admin_get_rules(request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    with _db() as conn:
        return {"rules": get_rules(conn), "limits": {k: {"default": d, "min": lo, "max": hi} for k, (d, lo, hi) in RULE_LIMITS.items()}}


@router.put("/v1/admin/rules")
def admin_set_rules(body: RulesUpdate, request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    for key, value in body.rules.items():
        if key not in RULE_LIMITS:
            raise HTTPException(status_code=400, detail=f"unknown rule: {key}")
        _, lo, hi = RULE_LIMITS[key]
        if not lo <= value <= hi:
            raise HTTPException(status_code=400, detail=f"{key} must be between {lo} and {hi}")
    with _db() as conn:
        for key, value in body.rules.items():
            conn.execute(
                "INSERT INTO app_settings (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                (key, value),
            )
        return {"rules": get_rules(conn)}


@router.get("/v1/admin/dashboard")
def admin_dashboard(request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    now = now_ms()
    with _db() as conn:
        one = lambda sql, *p: conn.execute(sql, p).fetchone()[0]  # noqa: E731
        return {
            "accounts": one("SELECT COUNT(*) FROM accounts"),
            "newToday": one("SELECT COUNT(*) FROM accounts WHERE created_at >= date_trunc('day', NOW() AT TIME ZONE 'Africa/Cairo') AT TIME ZONE 'Africa/Cairo'"),
            "new7": one("SELECT COUNT(*) FROM accounts WHERE created_at >= NOW() - INTERVAL '7 days'"),
            "suspended": one("SELECT COUNT(*) FROM accounts WHERE suspended_at IS NOT NULL"),
            "proAccounts": one(
                "SELECT COUNT(*) FROM accounts a WHERE EXISTS (SELECT 1 FROM pro_grants g WHERE g.email = lower(a.email) "
                "AND g.revoked_at IS NULL AND (g.ends_at IS NULL OR g.ends_at > %s))", now),
            "grantsActive": one("SELECT COUNT(*) FROM pro_grants WHERE revoked_at IS NULL AND (ends_at IS NULL OR ends_at > %s)", now),
            "expiring7": one(
                "SELECT COUNT(*) FROM pro_grants WHERE revoked_at IS NULL AND ends_at IS NOT NULL AND ends_at > %s AND ends_at < %s",
                now, now + 7 * DAY_MS),
            "pointsInCirculation": one("SELECT COALESCE(SUM(balance), 0) FROM accounts"),
            "videosAllTime": one("SELECT COUNT(*) FROM points_ledger WHERE reason = 'ad'"),
            "videos7": one("SELECT COUNT(*) FROM points_ledger WHERE reason = 'ad' AND created_at >= NOW() - INTERVAL '7 days'"),
            "pointsSpentAllTime": one("SELECT COALESCE(-SUM(delta), 0) FROM points_ledger WHERE reason = 'spend'"),
        }


@router.get("/v1/admin/health")
def admin_health(request: Request, x_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(request, x_admin_token)
    with _db() as conn:
        conn.execute("SELECT 1")
    return {
        "database": True,
        "googleConfigured": bool(google_client_ids()),
        "googleClientIds": len(google_client_ids()),
        "adminConfigured": len(admin_token()) >= 16,
        "serverTime": now_ms(),
    }


@router.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_page() -> str:
    from admin_page import ADMIN_PAGE

    return ADMIN_PAGE
