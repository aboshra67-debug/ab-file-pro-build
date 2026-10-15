"""P17 server tests against a real PostgreSQL (DATABASE_URL must point at a disposable database)."""
import os
import time
import uuid

import psycopg
import pytest
from fastapi.testclient import TestClient

import main

pytestmark = pytest.mark.skipif(not os.getenv("DATABASE_URL"), reason="needs PostgreSQL")


@pytest.fixture(autouse=True)
def clean_db():
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as conn:
        conn.execute("DROP TABLE IF EXISTS family_chat_messages, family_devices, family_sync_items, family_spaces CASCADE")
    main._rate_hits.clear()
    with main.db() as conn:
        main.init_schema(conn)
    yield


client = TestClient(main.app)


def dev() -> str:
    return "dev-" + uuid.uuid4().hex


def register(code="123456", device=None):
    device = device or dev()
    r = client.post(f"/v2/families/{code}/register", json={"deviceId": device})
    assert r.status_code == 201, r.text
    body = r.json()
    return device, body["deviceToken"], body["pairingSecret"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def sync(code, device, token, items=(), since=None):
    body = {"deviceId": device, "items": list(items)}
    if since is not None:
        body["since"] = since
    return client.post(f"/v2/families/{code}/sync", json=body, headers=auth(token))


def item(i, ts, deleted=False, payload=None):
    return {"id": f"item-{i:05d}", "updatedAt": ts, "deleted": deleted, "payload": payload or {"module": "shopping", "n": i}}


def test_health_reports_database_reachability():
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["databaseReachable"] is True


def test_join_requires_the_pairing_secret_not_just_the_code():
    code = "222222"
    _, _, secret = register(code)
    stranger = client.post(f"/v2/families/{code}/join", json={"deviceId": dev()})
    assert stranger.status_code == 403
    wrong = client.post(f"/v2/families/{code}/join", json={"deviceId": dev(), "pairingSecret": "AAAAAAAAAA"})
    assert wrong.status_code == 403
    ok = client.post(f"/v2/families/{code}/join", json={"deviceId": dev(), "pairingSecret": secret.lower()})
    assert ok.status_code == 200 and len(ok.json()["deviceToken"]) >= 32


def test_reopened_pairing_issues_a_new_secret_and_invalidates_the_old_one():
    code = "333333"
    device, token, first = register(code)
    reopened = client.post(f"/v2/families/{code}/pairing/open", json={"deviceId": device}, headers=auth(token))
    assert reopened.status_code == 200
    second = reopened.json()["pairingSecret"]
    assert second != first
    assert client.post(f"/v2/families/{code}/join", json={"deviceId": dev(), "pairingSecret": first}).status_code == 403
    assert client.post(f"/v2/families/{code}/join", json={"deviceId": dev(), "pairingSecret": second}).status_code == 200


def test_family_created_before_p17_cannot_be_joined_without_reopening():
    code = "444444"
    device, token, _ = register(code)
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as conn:
        conn.execute("UPDATE family_spaces SET pairing_secret_hash = NULL WHERE family_code = %s", (code,))
    assert client.post(f"/v2/families/{code}/join", json={"deviceId": dev(), "pairingSecret": "ANYTHING12"}).status_code == 423


def test_spoofed_forwarded_for_cannot_bypass_join_rate_limit():
    # 40 different codes (so the per-family limit never triggers), each with a different forged
    # left-most address; the proxy-appended right-most address is the same, so the IP limit holds.
    statuses = []
    for i in range(40):
        headers = {"X-Forwarded-For": f"10.0.{i}.1, 203.0.113.9"}
        statuses.append(client.post(f"/v2/families/{500000 + i}/join", json={"deviceId": dev(), "pairingSecret": "WRONG00000"}, headers=headers).status_code)
    assert statuses[:30] == [404] * 30 and statuses[30:] == [429] * 10


def test_pagination_returns_items_beyond_the_first_500():
    code = "666666"
    device, token, _ = register(code)
    base = int(time.time() * 1000) - 10_000_000
    for start in range(0, 1200, 400):
        assert sync(code, device, token, [item(i, base + i) for i in range(start, start + 400)]).status_code == 200
    seen, cursor, pages = [], {"updatedAt": 0, "id": ""}, 0
    while True:
        r = sync(code, device, token, since=cursor).json()
        seen += [x["id"] for x in r["items"]]
        cursor, pages = r["nextCursor"], pages + 1
        if not r["hasMore"]:
            break
    assert len(seen) == 1200 and len(set(seen)) == 1200 and pages == 3
    assert sync(code, device, token, since=cursor).json()["items"] == []


def test_legacy_clients_without_cursor_still_get_the_old_response_shape():
    code = "777777"
    device, token, _ = register(code)
    r = sync(code, device, token, [item(1, 1000)])
    assert r.status_code == 200 and [x["id"] for x in r.json()["items"]] == ["item-00001"]
    assert "hasMore" not in r.json()


def test_future_clock_is_clamped():
    code = "888888"
    device, token, _ = register(code)
    future = int(time.time() * 1000) + 365 * 24 * 3600 * 1000
    sync(code, device, token, [item(1, future)])
    stored = sync(code, device, token, since={"updatedAt": 0, "id": ""}).json()["items"][0]["updatedAt"]
    assert stored <= int(time.time() * 1000) + main.MAX_CLOCK_SKEW_MS


def test_oversized_payload_is_rejected():
    code = "999999"
    device, token, _ = register(code)
    r = sync(code, device, token, [item(1, 1000, payload={"blob": "x" * 20_000})])
    assert r.status_code == 413


def test_leave_with_delete_withdraws_my_items_and_chat_but_keeps_others():
    code = "121212"
    a, ta, secret = register(code)
    b = dev()
    tb = client.post(f"/v2/families/{code}/join", json={"deviceId": b, "pairingSecret": secret}).json()["deviceToken"]
    sync(code, a, ta, [item(1, 1000)])
    sync(code, b, tb, [item(2, 1001)])
    msg = {"deviceId": a, "messageId": "msg-" + uuid.uuid4().hex, "senderName": "A", "body": "hi", "createdAt": 1}
    assert client.post(f"/v2/families/{code}/chat/messages", json=msg, headers=auth(ta)).status_code == 201
    left = client.post(f"/v2/families/{code}/devices/leave", json={"deviceId": a, "deleteMyData": True}, headers=auth(ta))
    assert left.status_code == 200 and left.json()["withdrawnItems"] == 1 and left.json()["familyDeleted"] is False
    items = {x["id"]: x for x in sync(code, b, tb, since={"updatedAt": 0, "id": ""}).json()["items"]}
    assert items["item-00001"]["deleted"] is True and items["item-00001"]["payload"] == {}
    assert items["item-00002"]["deleted"] is False
    chat = client.post(f"/v2/families/{code}/chat/pull", json={"deviceId": b}, headers=auth(tb)).json()["messages"]
    assert chat == []
    assert sync(code, a, ta).status_code == 401


def test_last_device_leaving_deletes_the_family_space():
    code = "131313"
    a, ta, _ = register(code)
    sync(code, a, ta, [item(1, 1000)])
    r = client.post(f"/v2/families/{code}/devices/leave", json={"deviceId": a, "deleteMyData": False}, headers=auth(ta))
    assert r.json()["familyDeleted"] is True
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM family_sync_items WHERE family_code = %s", (code,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM family_spaces WHERE family_code = %s", (code,)).fetchone()[0] == 0
    assert client.post(f"/v2/families/{code}/register", json={"deviceId": dev()}).status_code == 201


def test_legacy_shared_key_endpoint_is_gone():
    assert client.post("/v1/families/123456/sync", json={}).status_code == 410


def test_update_channel_has_no_default_package(monkeypatch):
    monkeypatch.setattr(main, "UPDATE_VERSION_CODE", 999)
    monkeypatch.setattr(main, "UPDATE_VERSION_NAME", "x")
    monkeypatch.setattr(main, "UPDATE_APK_URL", "https://example.com/a.apk")
    monkeypatch.setattr(main, "UPDATE_SHA256", "a" * 64)
    assert client.get("/app-update", params={"packageName": "com.abfilepro.app.ocrpreviewp8"}).json() == {"available": False}


def test_delete_account_page_is_public():
    r = client.get("/delete-account")
    assert r.status_code == 200 and "Leave family and delete my data" in r.text
