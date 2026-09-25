import hashlib
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
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
APP_KEY = os.getenv("APP_KEY", "").strip()
MAX_ITEMS = 500
MAX_DEVICES_PER_FAMILY = 8
PAIRING_MINUTES_NEW = 15
PAIRING_MINUTES_REOPEN = 10

app = FastAPI(title="AB File Pro Family Sync", version="2.0.0")

_join_lock = Lock()
_join_ip_hits: dict[str, deque[float]] = defaultdict(deque)
_join_family_hits: dict[str, deque[float]] = defaultdict(deque)


class SyncItem(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    updatedAt: int = Field(ge=0)
    deleted: bool = False
    payload: dict[str, Any] = Field(default_factory=dict)
    comment: str = Field(default="", max_length=500)


class SyncRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    items: list[SyncItem] = Field(default_factory=list, max_length=MAX_ITEMS)


class DeviceRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)


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


def require_key(value: str | None) -> None:
    if not APP_KEY:
        raise HTTPException(status_code=503, detail="sync is not configured")
    if not value or value != APP_KEY:
        raise HTTPException(status_code=401, detail="unauthorized")


def validate_family_code(code: str) -> str:
    if len(code) != 6 or not code.isdigit():
        raise HTTPException(status_code=400, detail="family code must be exactly 6 digits")
    return code


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def make_device_token() -> str:
    return secrets.token_urlsafe(32)


def bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="missing device token")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or len(token) < 32:
        raise HTTPException(status_code=401, detail="invalid device token")
    return token


def client_key(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    raw = forwarded.split(",", 1)[0].strip() if forwarded else (request.client.host if request.client else "unknown")
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def check_join_rate(request: Request, family_code: str) -> None:
    now = time.time()
    with _join_lock:
        ip_hits = _join_ip_hits[client_key(request)]
        family_hits = _join_family_hits[family_code]
        for q in (ip_hits, family_hits):
            while q and now - q[0] > 60:
                q.popleft()
        if len(ip_hits) >= 30 or len(family_hits) >= 12:
            raise HTTPException(status_code=429, detail="too many pairing attempts")
        ip_hits.append(now)
        family_hits.append(now)


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


def upsert_sync_items(conn, family_code: str, items: list[SyncItem]) -> None:
    for item in items:
        payload = item.payload if not item.deleted else {}
        comment = item.comment if not item.deleted else ""
        conn.execute(
            """
            INSERT INTO family_sync_items
                (family_code, item_id, updated_at, deleted, payload, comment)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (family_code, item_id) DO UPDATE SET
                updated_at = EXCLUDED.updated_at,
                deleted = EXCLUDED.deleted,
                payload = EXCLUDED.payload,
                comment = EXCLUDED.comment
            WHERE EXCLUDED.updated_at >= family_sync_items.updated_at
            """,
            (family_code, item.id, item.updatedAt, item.deleted, Jsonb(payload), comment),
        )


def read_sync_items(conn, family_code: str) -> list[dict[str, Any]]:
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
    return [
        {
            "id": row[0],
            "updatedAt": row[1],
            "deleted": row[2],
            "payload": row[3] or {},
            "comment": row[4] or "",
        }
        for row in rows
    ]


@app.on_event("startup")
def init_db() -> None:
    if not DATABASE_URL:
        return
    with db() as conn:
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
        conn.execute(
            "CREATE INDEX IF NOT EXISTS family_sync_items_family_updated_idx "
            "ON family_sync_items (family_code, updated_at)"
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


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "databaseConfigured": bool(DATABASE_URL),
        "legacyAppKeyConfigured": bool(APP_KEY),
        "familyAuthVersion": 2,
    }


@app.post("/v2/families/{family_code}/register", status_code=201)
def register_family(family_code: str, body: DeviceRequest) -> dict[str, Any]:
    code = validate_family_code(family_code)
    token = make_device_token()
    until = datetime.now(timezone.utc) + timedelta(minutes=PAIRING_MINUTES_NEW)
    with db() as conn:
        row = conn.execute(
            """
            INSERT INTO family_spaces (family_code, pairing_open_until)
            VALUES (%s, %s)
            ON CONFLICT (family_code) DO NOTHING
            RETURNING family_code
            """,
            (code, until),
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
        "pairingOpenUntil": until.isoformat(),
    }


@app.post("/v2/families/{family_code}/join")
def join_family(family_code: str, body: DeviceRequest, request: Request) -> dict[str, Any]:
    code = validate_family_code(family_code)
    check_join_rate(request, code)
    token = make_device_token()
    with db() as conn:
        space = conn.execute(
            "SELECT pairing_open_until FROM family_spaces WHERE family_code = %s",
            (code,),
        ).fetchone()
        if not space:
            raise HTTPException(status_code=404, detail="family not found")
        if space[0] <= datetime.now(timezone.utc):
            raise HTTPException(status_code=423, detail="pairing window is closed")

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
    with db() as conn:
        require_device(conn, code, authorization, body.deviceId)
        conn.execute(
            "UPDATE family_spaces SET pairing_open_until = %s WHERE family_code = %s",
            (until, code),
        )
    return {"familyCode": code, "pairingOpenUntil": until.isoformat()}


@app.post("/v2/families/{family_code}/sync")
def sync_family_v2(
    family_code: str,
    body: SyncRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    code = validate_family_code(family_code)
    with db() as conn:
        require_device(conn, code, authorization, body.deviceId)
        upsert_sync_items(conn, code, body.items)
        items = read_sync_items(conn, code)
    return {
        "familyCode": code,
        "serverTime": int(time.time() * 1000),
        "items": items,
    }


# Legacy Alpha82 endpoint retained temporarily for migration/testing.
@app.post("/v1/families/{family_code}/sync")
def sync_family_legacy(
    family_code: str,
    body: SyncRequest,
    x_app_key: str | None = Header(default=None),
) -> dict[str, Any]:
    require_key(x_app_key)
    code = validate_family_code(family_code)
    with db() as conn:
        upsert_sync_items(conn, code, body.items)
        items = read_sync_items(conn, code)
    return {
        "familyCode": code,
        "serverTime": int(time.time() * 1000),
        "items": items,
    }
