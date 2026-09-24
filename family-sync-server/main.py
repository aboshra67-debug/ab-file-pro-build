import os
from contextlib import contextmanager
from typing import Any

import psycopg
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
APP_KEY = os.getenv("APP_KEY", "").strip()
MAX_ITEMS = 500

app = FastAPI(title="AB File Pro Family Sync", version="1.0.0")


class SyncItem(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    updatedAt: int = Field(ge=0)
    deleted: bool = False
    payload: dict[str, Any] = Field(default_factory=dict)
    comment: str = Field(default="", max_length=500)


class SyncRequest(BaseModel):
    deviceId: str = Field(min_length=8, max_length=100)
    items: list[SyncItem] = Field(default_factory=list, max_length=MAX_ITEMS)


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
    clean = "".join(ch for ch in code if ch.isdigit())
    if len(clean) != 6 or clean != code:
        raise HTTPException(status_code=400, detail="family code must be exactly 6 digits")
    return clean


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


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "databaseConfigured": bool(DATABASE_URL), "appKeyConfigured": bool(APP_KEY)}


@app.post("/v1/families/{family_code}/sync")
def sync_family(
    family_code: str,
    body: SyncRequest,
    request: Request,
    x_app_key: str | None = Header(default=None),
) -> dict[str, Any]:
    del request
    require_key(x_app_key)
    code = validate_family_code(family_code)

    with db() as conn:
        for item in body.items:
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
                (code, item.id, item.updatedAt, item.deleted, Jsonb(payload), comment),
            )

        rows = conn.execute(
            """
            SELECT item_id, updated_at, deleted, payload, comment
            FROM family_sync_items
            WHERE family_code = %s
            ORDER BY updated_at ASC, item_id ASC
            LIMIT %s
            """,
            (code, MAX_ITEMS),
        ).fetchall()

    return {
        "familyCode": code,
        "serverTime": int(__import__("time").time() * 1000),
        "items": [
            {
                "id": row[0],
                "updatedAt": row[1],
                "deleted": row[2],
                "payload": row[3] or {},
                "comment": row[4] or "",
            }
            for row in rows
        ],
    }
