import hashlib
import hmac
import json
import os
import secrets
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from threading import Lock
from typing import Any

import psycopg
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
SUPPORT_EMAIL = os.getenv("AB_FILE_PRO_SUPPORT_EMAIL", "").strip()

try:
    UPDATE_VERSION_CODE = int(os.getenv("AB_FILE_PRO_UPDATE_VERSION_CODE", "0") or "0")
except ValueError:
    UPDATE_VERSION_CODE = 0
UPDATE_VERSION_NAME = os.getenv("AB_FILE_PRO_UPDATE_VERSION_NAME", "").strip()
UPDATE_APK_URL = os.getenv("AB_FILE_PRO_UPDATE_APK_URL", "").strip()
UPDATE_SHA256 = os.getenv("AB_FILE_PRO_UPDATE_SHA256", "").strip().lower()
UPDATE_NOTES = os.getenv("AB_FILE_PRO_UPDATE_NOTES", "").strip()
UPDATE_MANDATORY = os.getenv("AB_FILE_PRO_UPDATE_MANDATORY", "false").strip().lower() in {"1", "true", "yes", "on"}
# P17: no implicit default package. The direct (non-Play) channel must be configured explicitly.
UPDATE_PACKAGES = {
    value.strip()
    for value in os.getenv("AB_FILE_PRO_UPDATE_PACKAGES", "").split(",")
    if value.strip()
}
MAX_ITEMS = 500
MAX_PAGE = 500
MAX_CHAT_MESSAGES = 300
MAX_DEVICES_PER_FAMILY = 8
MAX_PAYLOAD_BYTES = 16_000
MAX_BODY_BYTES = 2_500_000
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000
PAIRING_MINUTES_NEW = 15
PAIRING_MINUTES_REOPEN = 10
SECRET_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"  # no 0/1/I/L/O/U look-alikes
SECRET_LENGTH = 10

app = FastAPI(title="AB File Pro Family Sync", version="4.0.0")

_rate_lock = Lock()
_rate_hits: dict[str, deque[float]] = defaultdict(deque)


class SyncCursor(BaseModel):
    updatedAt: int = Field(default=0, ge=0)
    id: str = Field(default="", max_length=100)


class SyncItem(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    updatedAt: int = Field(ge=0)
    deleted: bool = False
    payload: dict[str, Any] = Field(default_factory=dict)
    comment: str = Field(default="", max_length=500)


class SyncRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    items: list[SyncItem] = Field(default_factory=list, max_length=MAX_ITEMS)
    since: SyncCursor | None = None


class DeviceRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)


class JoinRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    pairingSecret: str = Field(default="", max_length=40)


class LeaveRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    deleteMyData: bool = True


class ChatMessageCreate(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    messageId: str = Field(min_length=8, max_length=100)
    senderName: str = Field(min_length=1, max_length=80)
    body: str = Field(min_length=1, max_length=2000)
    createdAt: int = Field(ge=0)


@app.middleware("http")
async def limit_body_size(request: Request, call_next):
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > MAX_BODY_BYTES:
                return JSONResponse(status_code=413, content={"detail": "request too large"})
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "invalid content-length"})
    return await call_next(request)


@contextmanager
def db():
    if not DATABASE_URL:
        raise RuntimeError("DATABASE_URL is not configured")
    conn = psycopg.connect(DATABASE_URL, autocommit=False)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def validate_family_code(code: str) -> str:
    if len(code) != 6 or not code.isdigit():
        raise HTTPException(status_code=400, detail="family code must be exactly 6 digits")
    return code


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def secret_hash(code: str, secret: str) -> str:
    return hashlib.sha256(f"{code}:{secret.strip().upper()}".encode("utf-8")).hexdigest()


def make_device_token() -> str:
    return secrets.token_urlsafe(32)


def make_pairing_secret() -> str:
    return "".join(secrets.choice(SECRET_ALPHABET) for _ in range(SECRET_LENGTH))


def bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="missing device token")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or len(token) < 32:
        raise HTTPException(status_code=401, detail="invalid device token")
    return token


def client_key(request: Request) -> str:
    # P17: the client controls the left side of X-Forwarded-For. The right-most entry is the one
    # appended by the platform proxy in front of this service, so it cannot be spoofed by the caller.
    forwarded = [part.strip() for part in request.headers.get("x-forwarded-for", "").split(",") if part.strip()]
    raw = forwarded[-1] if forwarded else (request.client.host if request.client else "unknown")
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def check_rate(bucket: str, limit: int, window_seconds: float = 60.0) -> None:
    now = time.time()
    with _rate_lock:
        hits = _rate_hits[bucket]
        while hits and now - hits[0] > window_seconds:
            hits.popleft()
        if len(hits) >= limit:
            raise HTTPException(status_code=429, detail="too many attempts")
        hits.append(now)


def require_device(conn, family_code: str, authorization: str | None, device_id: str | None = None) -> str:
    token = bearer_token(authorization)
    row = conn.execute(
        """
        SELECT device_id
        FROM family_devices
        WHERE family_code = %s AND token_hash = %s
        """,
        (family_code, token_hash(token)),
    ).fetchone()
    if not row:
        raise HTTPException(status_code=401, detail="invalid device token")
    registered_device = row[0]
    if device_id and registered_device != device_id:
        raise HTTPException(status_code=401, detail="device token mismatch")
    conn.execute(
        "UPDATE family_devices SET last_seen_at = NOW() WHERE family_code = %s AND device_id = %s",
        (family_code, registered_device),
    )
    return registered_device


def upsert_sync_items(conn, family_code: str, device_id: str, items: list[SyncItem]) -> None:
    latest_allowed = int(time.time() * 1000) + MAX_CLOCK_SKEW_MS
    for item in items:
        payload = item.payload if not item.deleted else {}
        if len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) > MAX_PAYLOAD_BYTES:
            raise HTTPException(status_code=413, detail="item payload too large")
        comment = item.comment if not item.deleted else ""
        # P17 (FAM-05): a device clock far in the future must not win every later edit.
        updated_at = min(item.updatedAt, latest_allowed)
        conn.execute(
            """
            INSERT INTO family_sync_items
                (family_code, item_id, updated_at, deleted, payload, comment, origin_device)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (family_code, item_id) DO UPDATE SET
                updated_at = EXCLUDED.updated_at,
                deleted = EXCLUDED.deleted,
                payload = EXCLUDED.payload,
                comment = EXCLUDED.comment,
                origin_device = COALESCE(family_sync_items.origin_device, EXCLUDED.origin_device)
            WHERE EXCLUDED.updated_at >= family_sync_items.updated_at
            """,
            (family_code, item.id, updated_at, item.deleted, Jsonb(payload), comment, device_id),
        )


def item_row(row) -> dict[str, Any]:
    return {
        "id": row[0],
        "updatedAt": row[1],
        "deleted": row[2],
        "payload": row[3] or {},
        "comment": row[4] or "",
    }


def read_sync_items_legacy(conn, family_code: str) -> list[dict[str, Any]]:
    """Pre-P17 clients (no cursor): unchanged response so already installed apps keep working."""
    rows = conn.execute(
        """
        SELECT item_id, updated_at, deleted, payload, comment
        FROM family_sync_items
        WHERE family_code = %s
        ORDER BY updated_at ASC, item_id ASC
        LIMIT %s
        """,
        (family_code, MAX_ITEMS),
    ).fetchall()
    return [item_row(row) for row in rows]


def read_sync_page(conn, family_code: str, since: SyncCursor) -> tuple[list[dict[str, Any]], dict[str, Any], bool]:
    """P17 (FAM-02): keyset pagination, so newer items and tombstones are never hidden behind 500 old ones."""
    rows = conn.execute(
        """
        SELECT item_id, updated_at, deleted, payload, comment
        FROM family_sync_items
        WHERE family_code = %s AND (updated_at, item_id) > (%s, %s)
        ORDER BY updated_at ASC, item_id ASC
        LIMIT %s
        """,
        (family_code, since.updatedAt, since.id, MAX_PAGE + 1),
    ).fetchall()
    has_more = len(rows) > MAX_PAGE
    rows = rows[:MAX_PAGE]
    items = [item_row(row) for row in rows]
    cursor = {"updatedAt": rows[-1][1], "id": rows[-1][0]} if rows else {"updatedAt": since.updatedAt, "id": since.id}
    return items, cursor, has_more


def read_chat_messages(conn, family_code: str) -> list[dict[str, Any]]:
    rows = conn.execute(
        """
        SELECT message_id, device_id, sender_name, body, created_at
        FROM (
            SELECT message_id, device_id, sender_name, body, created_at
            FROM family_chat_messages
            WHERE family_code = %s
            ORDER BY created_at DESC, stored_at DESC
            LIMIT %s
        ) latest
        ORDER BY created_at ASC, message_id ASC
        """,
        (family_code, MAX_CHAT_MESSAGES),
    ).fetchall()
    return [
        {
            "messageId": row[0],
            "deviceId": row[1],
            "senderName": row[2],
            "body": row[3],
            "createdAt": row[4],
        }
        for row in rows
    ]


def init_schema(conn) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS family_sync_items (
            family_code VARCHAR(6) NOT NULL,
            item_id VARCHAR(100) NOT NULL,
            updated_at BIGINT NOT NULL,
            deleted BOOLEAN NOT NULL DEFAULT FALSE,
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            comment TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (family_code, item_id)
        )
        """
    )
    conn.execute("ALTER TABLE family_sync_items ADD COLUMN IF NOT EXISTS origin_device VARCHAR(100)")
    conn.execute(
        "CREATE INDEX IF NOT EXISTS family_sync_items_family_updated_idx "
        "ON family_sync_items (family_code, updated_at)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS family_sync_items_family_cursor_idx "
        "ON family_sync_items (family_code, updated_at, item_id)"
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS family_spaces (
            family_code VARCHAR(6) PRIMARY KEY,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            pairing_open_until TIMESTAMPTZ NOT NULL
        )
        """
    )
    conn.execute("ALTER TABLE family_spaces ADD COLUMN IF NOT EXISTS pairing_secret_hash CHAR(64)")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS family_devices (
            family_code VARCHAR(6) NOT NULL REFERENCES family_spaces(family_code) ON DELETE CASCADE,
            device_id VARCHAR(100) NOT NULL,
            token_hash CHAR(64) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (family_code, device_id),
            UNIQUE (family_code, token_hash)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS family_chat_messages (
            family_code VARCHAR(6) NOT NULL REFERENCES family_spaces(family_code) ON DELETE CASCADE,
            message_id VARCHAR(100) NOT NULL,
            device_id VARCHAR(100) NOT NULL,
            sender_name VARCHAR(80) NOT NULL,
            body TEXT NOT NULL,
            created_at BIGINT NOT NULL,
            stored_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (family_code, message_id)
        )
        """
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS family_chat_messages_family_created_idx "
        "ON family_chat_messages (family_code, created_at DESC)"
    )


@app.on_event("startup")
def init_db() -> None:
    if not DATABASE_URL:
        return
    with db() as conn:
        init_schema(conn)


@app.get("/health")
def health():
    # P17 (FAM-N05): readiness means the database actually answers.
    body: dict[str, Any] = {
        "ok": False,
        "databaseConfigured": bool(DATABASE_URL),
        "databaseReachable": False,
        "familyAuthVersion": 3,
        "familyChatVersion": 1,
        "syncPagination": True,
    }
    if DATABASE_URL:
        try:
            with db() as conn:
                conn.execute("SELECT 1").fetchone()
            body["databaseReachable"] = True
            body["ok"] = True
        except Exception:
            pass
    return JSONResponse(status_code=200 if body["ok"] else 503, content=body)


@app.get("/app-update")
def app_update(packageName: str, currentVersionCode: int = 0) -> dict[str, Any]:
    configured = (
        UPDATE_VERSION_CODE > 0
        and bool(UPDATE_VERSION_NAME)
        and UPDATE_APK_URL.startswith("https://")
        and len(UPDATE_SHA256) == 64
        and all(ch in "0123456789abcdef" for ch in UPDATE_SHA256)
    )
    available = (
        configured
        and packageName in UPDATE_PACKAGES
        and currentVersionCode < UPDATE_VERSION_CODE
    )
    if not available:
        return {"available": False}
    return {
        "available": True,
        "versionCode": UPDATE_VERSION_CODE,
        "versionName": UPDATE_VERSION_NAME,
        "apkUrl": UPDATE_APK_URL,
        "sha256": UPDATE_SHA256,
        "notes": UPDATE_NOTES,
        "mandatory": UPDATE_MANDATORY,
    }


@app.post("/v2/families/{family_code}/register", status_code=201)
def register_family(family_code: str, body: DeviceRequest, request: Request) -> dict[str, Any]:
    code = validate_family_code(family_code)
    check_rate("register-ip:" + client_key(request), 10)
    check_rate("register-all", 600)
    token = make_device_token()
    pairing_secret = make_pairing_secret()
    until = datetime.now(timezone.utc) + timedelta(minutes=PAIRING_MINUTES_NEW)
    with db() as conn:
        row = conn.execute(
            """
            INSERT INTO family_spaces (family_code, pairing_open_until, pairing_secret_hash)
            VALUES (%s, %s, %s)
            ON CONFLICT (family_code) DO NOTHING
            RETURNING family_code
            """,
            (code, until, secret_hash(code, pairing_secret)),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=409, detail="family already exists")
        conn.execute(
            """
            INSERT INTO family_devices (family_code, device_id, token_hash)
            VALUES (%s, %s, %s)
            """,
            (code, body.deviceId, token_hash(token)),
        )
    return {
        "familyCode": code,
        "deviceToken": token,
        "pairingSecret": pairing_secret,
        "pairingOpenUntil": until.isoformat(),
    }


@app.post("/v2/families/{family_code}/join")
def join_family(family_code: str, body: JoinRequest, request: Request) -> dict[str, Any]:
    code = validate_family_code(family_code)
    check_rate("join-ip:" + client_key(request), 30)
    check_rate("join-family:" + code, 12)
    check_rate("join-all", 600)
    token = make_device_token()
    with db() as conn:
        space = conn.execute(
            "SELECT pairing_open_until, pairing_secret_hash FROM family_spaces WHERE family_code = %s",
            (code,),
        ).fetchone()
        if not space:
            raise HTTPException(status_code=404, detail="family not found")
        if space[0] <= datetime.now(timezone.utc):
            raise HTTPException(status_code=423, detail="pairing window is closed")
        # P17: the 6-digit code alone is no longer enough. A per-window secret, shown only on an
        # already linked device, is required. Families created before P17 must reopen pairing first.
        if not space[1]:
            raise HTTPException(status_code=423, detail="reopen pairing on a linked device")
        if not body.pairingSecret or not hmac.compare_digest(space[1], secret_hash(code, body.pairingSecret)):
            raise HTTPException(status_code=403, detail="invalid invite")

        known = conn.execute(
            "SELECT 1 FROM family_devices WHERE family_code = %s AND device_id = %s",
            (code, body.deviceId),
        ).fetchone()
        if not known:
            count = conn.execute(
                "SELECT COUNT(*) FROM family_devices WHERE family_code = %s",
                (code,),
            ).fetchone()[0]
            if count >= MAX_DEVICES_PER_FAMILY:
                raise HTTPException(status_code=409, detail="family device limit reached")

        conn.execute(
            """
            INSERT INTO family_devices (family_code, device_id, token_hash)
            VALUES (%s, %s, %s)
            ON CONFLICT (family_code, device_id) DO UPDATE SET
                token_hash = EXCLUDED.token_hash,
                last_seen_at = NOW()
            """,
            (code, body.deviceId, token_hash(token)),
        )
    return {"familyCode": code, "deviceToken": token}


@app.post("/v2/families/{family_code}/pairing/open")
def open_pairing(
    family_code: str,
    body: DeviceRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    code = validate_family_code(family_code)
    until = datetime.now(timezone.utc) + timedelta(minutes=PAIRING_MINUTES_REOPEN)
    pairing_secret = make_pairing_secret()
    with db() as conn:
        require_device(conn, code, authorization, body.deviceId)
        conn.execute(
            "UPDATE family_spaces SET pairing_open_until = %s, pairing_secret_hash = %s WHERE family_code = %s",
            (until, secret_hash(code, pairing_secret), code),
        )
    return {"familyCode": code, "pairingSecret": pairing_secret, "pairingOpenUntil": until.isoformat()}


@app.post("/v2/families/{family_code}/devices/leave")
def leave_family(
    family_code: str,
    body: LeaveRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """P17 (FAM-N06): unlink this device; optionally withdraw everything it shared."""
    code = validate_family_code(family_code)
    now_ms = int(time.time() * 1000)
    with db() as conn:
        device = require_device(conn, code, authorization, body.deviceId)
        withdrawn = 0
        if body.deleteMyData:
            # Tombstones (not hard deletes) so the other devices remove their copies on next sync.
            withdrawn = conn.execute(
                """
                UPDATE family_sync_items
                SET deleted = TRUE, payload = '{}'::jsonb, comment = '',
                    updated_at = GREATEST(updated_at + 1, %s)
                WHERE family_code = %s AND origin_device = %s AND deleted = FALSE
                """,
                (now_ms, code, device),
            ).rowcount
            conn.execute(
                "DELETE FROM family_chat_messages WHERE family_code = %s AND device_id = %s",
                (code, device),
            )
        conn.execute("DELETE FROM family_devices WHERE family_code = %s AND device_id = %s", (code, device))
        remaining = conn.execute(
            "SELECT COUNT(*) FROM family_devices WHERE family_code = %s", (code,)
        ).fetchone()[0]
        family_deleted = False
        if remaining == 0:
            conn.execute("DELETE FROM family_sync_items WHERE family_code = %s", (code,))
            conn.execute("DELETE FROM family_spaces WHERE family_code = %s", (code,))
            family_deleted = True
    return {"familyCode": code, "left": True, "withdrawnItems": withdrawn, "familyDeleted": family_deleted}


@app.post("/v2/families/{family_code}/sync")
def sync_family_v2(
    family_code: str,
    body: SyncRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    code = validate_family_code(family_code)
    with db() as conn:
        device = require_device(conn, code, authorization, body.deviceId)
        upsert_sync_items(conn, code, device, body.items)
        if body.since is None:
            items = read_sync_items_legacy(conn, code)
            return {"familyCode": code, "serverTime": int(time.time() * 1000), "items": items}
        items, cursor, has_more = read_sync_page(conn, code, body.since)
    return {
        "familyCode": code,
        "serverTime": int(time.time() * 1000),
        "items": items,
        "nextCursor": cursor,
        "hasMore": has_more,
    }


@app.post("/v2/families/{family_code}/chat/messages", status_code=201)
def send_chat_message(
    family_code: str,
    body: ChatMessageCreate,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    code = validate_family_code(family_code)
    sender_name = body.senderName.strip()[:80]
    message_body = body.body.strip()[:2000]
    if not sender_name or not message_body:
        raise HTTPException(status_code=400, detail="sender name and message body are required")
    with db() as conn:
        require_device(conn, code, authorization, body.deviceId)
        conn.execute(
            """
            INSERT INTO family_chat_messages
                (family_code, message_id, device_id, sender_name, body, created_at)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (family_code, message_id) DO NOTHING
            """,
            (code, body.messageId, body.deviceId, sender_name, message_body, body.createdAt),
        )
    return {
        "messageId": body.messageId,
        "deviceId": body.deviceId,
        "senderName": sender_name,
        "body": message_body,
        "createdAt": body.createdAt,
    }


@app.post("/v2/families/{family_code}/chat/pull")
def pull_chat_messages(
    family_code: str,
    body: DeviceRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    code = validate_family_code(family_code)
    with db() as conn:
        require_device(conn, code, authorization, body.deviceId)
        messages = read_chat_messages(conn, code)
    return {
        "familyCode": code,
        "serverTime": int(time.time() * 1000),
        "messages": messages,
    }


# P17 (FAM-N03): the Alpha82 shared-key endpoint is retired; it bypassed per-device auth.
@app.post("/v1/families/{family_code}/sync")
def sync_family_legacy(family_code: str) -> dict[str, Any]:
    raise HTTPException(status_code=410, detail="legacy sync endpoint removed; update the app")


@app.get("/delete-account", response_class=HTMLResponse)
def delete_account_page() -> str:
    """Public page for the Google Play data-deletion link."""
    contact = (
        f'<p>Or email <a href="mailto:{SUPPORT_EMAIL}">{SUPPORT_EMAIL}</a> with your family code.</p>'
        f'<p dir="rtl">أو راسلنا على <a href="mailto:{SUPPORT_EMAIL}">{SUPPORT_EMAIL}</a> مع كود العائلة.</p>'
        if SUPPORT_EMAIL else ""
    )
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AB File Pro - Delete family data</title>
<style>body{{font-family:system-ui,sans-serif;max-width:640px;margin:2rem auto;padding:0 1rem;line-height:1.6}}</style></head>
<body>
<h1>Delete your AB File Pro family data</h1>
<p>AB File Pro keeps your files, scans and assistant data on your phone. Only the optional family feature
stores shared items (family requests, shopping list, and health items you chose to share) and family chat on this service.</p>
<ol><li>Open AB File Pro &rarr; Personal assistant &rarr; Family requests &rarr; Settings.</li>
<li>Tap <b>Leave family and delete my data</b>.</li></ol>
<p>This removes your device link, the items you shared and your chat messages. When the last device leaves,
the whole family space is deleted.</p>
<div dir="rtl"><h2>حذف بيانات العائلة</h2>
<p>افتح AB File Pro ← المساعد الشخصي ← طلبات العائلة ← الإعدادات، ثم اضغط <b>مغادرة العائلة وحذف بياناتي</b>.
يُحذف ربط جهازك وما شاركته ورسائلك، وعند خروج آخر جهاز تُحذف مساحة العائلة كاملة.</p></div>
{contact}
</body></html>"""
