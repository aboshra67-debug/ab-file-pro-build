"""P37 account tests against a real PostgreSQL (same disposable database as test_main.py)."""
import json
import os
import time
import uuid

import jwt
import psycopg
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

import accounts
import main

pytestmark = pytest.mark.skipif(not os.getenv("DATABASE_URL"), reason="needs PostgreSQL")

CLIENT_ID = "test-web-client.apps.googleusercontent.com"
ADMIN = "admin-token-for-tests-1234"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
JWK = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(KEY.public_key()))
JWK.update(kid="k1", alg="RS256", use="sig")

client = TestClient(main.app)


@pytest.fixture(autouse=True)
def fresh(monkeypatch):
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as conn:
        conn.execute(
            "DROP TABLE IF EXISTS family_chat_messages, family_devices, family_sync_items, family_spaces, "
            "points_ledger, account_sessions, accounts, pro_grants CASCADE"
        )
    main._rate_hits.clear()
    with main.db() as conn:
        main.init_schema(conn)
    monkeypatch.setenv("GOOGLE_CLIENT_IDS", CLIENT_ID)
    monkeypatch.setenv("ADMIN_TOKEN", ADMIN)
    monkeypatch.setattr(accounts, "jwks_provider", lambda: [JWK])
    yield


def id_token(sub="sub-1", email="ali@example.com", name="Ali", aud=CLIENT_ID, exp=None, verified=True, key=KEY, iss="https://accounts.google.com"):
    payload = {"sub": sub, "email": email, "name": name, "aud": aud, "iss": iss, "email_verified": verified,
               "iat": int(time.time()), "exp": exp or int(time.time()) + 600}
    return jwt.encode(payload, key, algorithm="RS256", headers={"kid": "k1"})


def sign_in(**kw):
    body = {"idToken": id_token(**{k: v for k, v in kw.items() if k not in ("localBalance", "deviceId")}), "deviceId": kw.get("deviceId", "dev-1")}
    if "localBalance" in kw:
        body["localBalance"] = kw["localBalance"]
    return client.post("/v1/auth/google", json=body)


def login(**kw):
    r = sign_in(**kw)
    assert r.status_code == 200, r.text
    return r.json()["token"], r.json()


def h(token):
    return {"Authorization": f"Bearer {token}"}


def admin(method, path, **kw):
    return client.request(method, path, headers={"X-Admin-Token": ADMIN}, **kw)


def set_days_back(email, field_values):
    sets = ", ".join(f"{k} = %s" for k in field_values)
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as conn:
        conn.execute(f"UPDATE accounts SET {sets} WHERE email = %s", (*field_values.values(), email))


# ------------------------------------------------------------------ sign-in ----------

def test_first_sign_in_creates_an_account_with_the_starting_gift():
    token, body = login()
    assert body["created"] is True
    acc = body["account"]
    assert acc["email"] == "ali@example.com" and acc["points"]["balance"] == 20
    assert acc["pro"]["active"] is False and acc["points"]["adsLeftToday"] == 3
    assert acc["points"]["dailyBonusAvailable"] is False  # the starting gift is the day-one gift


def test_second_sign_in_returns_the_same_account_on_another_device():
    t1, first = login(deviceId="phone-a")
    client.post("/v1/account/points/spend", json={"key": "spend-0001", "amount": 5}, headers=h(t1))
    t2, second = login(deviceId="phone-b")
    assert second["created"] is False
    assert second["account"]["points"]["balance"] == 15
    assert t1 != t2


def test_points_the_phone_already_had_are_kept_only_for_a_new_account():
    _, body = login(localBalance=7)
    assert body["account"]["points"]["balance"] == 7
    _, again = login(localBalance=99)
    assert again["account"]["points"]["balance"] == 7  # an existing account never takes the phone's word


@pytest.mark.parametrize("kw", [
    {"aud": "someone-elses-client"},
    {"exp": int(time.time()) - 3600},
    {"key": OTHER_KEY},
    {"iss": "https://evil.example.com"},
    {"verified": False},
])
def test_invalid_google_tokens_are_refused(kw):
    r = sign_in(**kw)
    assert r.status_code == 401, r.text


def test_garbage_token_is_refused():
    r = client.post("/v1/auth/google", json={"idToken": "x" * 40, "deviceId": "d"})
    assert r.status_code == 401


def test_sign_in_is_unavailable_until_google_is_configured(monkeypatch):
    monkeypatch.delenv("GOOGLE_CLIENT_IDS", raising=False)
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    assert sign_in().status_code == 503


def test_endpoints_need_a_session():
    assert client.get("/v1/account").status_code == 401
    assert client.get("/v1/account", headers=h("nope")).status_code == 401


def test_sign_out_ends_only_that_session():
    t1, _ = login(deviceId="a")
    t2, _ = login(deviceId="b")
    assert client.post("/v1/account/sign-out", headers=h(t1)).status_code == 200
    assert client.get("/v1/account", headers=h(t1)).status_code == 401
    assert client.get("/v1/account", headers=h(t2)).status_code == 200


# ------------------------------------------------------------------ points ----------

def test_spending_points_is_server_side_and_never_goes_below_zero():
    t, _ = login()
    r = client.post("/v1/account/points/spend", json={"key": "spend-0001", "amount": 20}, headers=h(t))
    assert r.status_code == 200 and r.json()["balance"] == 0 and r.json()["applied"] is True
    r = client.post("/v1/account/points/spend", json={"key": "spend-0002", "amount": 1}, headers=h(t))
    assert r.status_code == 402
    assert client.get("/v1/account", headers=h(t)).json()["points"]["balance"] == 0


def test_a_retried_spend_is_charged_once():
    t, _ = login()
    first = client.post("/v1/account/points/spend", json={"key": "spend-same1", "amount": 4}, headers=h(t)).json()
    again = client.post("/v1/account/points/spend", json={"key": "spend-same1", "amount": 4}, headers=h(t)).json()
    assert first["balance"] == 16 and again["balance"] == 16 and again["applied"] is False


def test_a_refused_spend_does_not_burn_its_key():
    t, _ = login(localBalance=3)
    assert client.post("/v1/account/points/spend", json={"key": "spend-retry", "amount": 5}, headers=h(t)).status_code == 402
    admin("POST", "/v1/admin/points", json={"email": "ali@example.com", "delta": 10})
    r = client.post("/v1/account/points/spend", json={"key": "spend-retry", "amount": 5}, headers=h(t))
    assert r.status_code == 200 and r.json()["balance"] == 8


def test_spend_amount_is_validated():
    t, _ = login()
    assert client.post("/v1/account/points/spend", json={"key": "spend-zero1", "amount": 0}, headers=h(t)).status_code == 400
    assert client.post("/v1/account/points/spend", json={"key": "spend-big01", "amount": 21}, headers=h(t)).status_code == 422
    assert client.post("/v1/account/points/spend", json={"key": "bad key!!", "amount": 1}, headers=h(t)).status_code == 422


def test_daily_gift_once_per_day_and_capped():
    t, _ = login()
    assert client.post("/v1/account/points/daily-bonus", headers=h(t)).json()["applied"] is False  # first day
    set_days_back("ali@example.com", {"last_bonus_day": accounts.today() - 1})
    r = client.post("/v1/account/points/daily-bonus", headers=h(t)).json()
    assert r["applied"] is True and r["balance"] == 22
    assert client.post("/v1/account/points/daily-bonus", headers=h(t)).json()["applied"] is False
    set_days_back("ali@example.com", {"last_bonus_day": accounts.today() - 1, "balance": 99})
    assert client.post("/v1/account/points/daily-bonus", headers=h(t)).json()["balance"] == 100


def test_rewarded_videos_three_a_day_and_each_counted_once():
    t, _ = login()
    balances = []
    for i in range(3):
        r = client.post("/v1/account/points/earn-ad", json={"key": f"video-000{i}"}, headers=h(t)).json()
        assert r["applied"] is True
        balances.append(r["balance"])
    assert balances == [25, 30, 35]
    fourth = client.post("/v1/account/points/earn-ad", json={"key": "video-0009"}, headers=h(t)).json()
    assert fourth["applied"] is False and fourth["adsLeftToday"] == 0 and fourth["balance"] == 35
    # a new day opens the limit again
    set_days_back("ali@example.com", {"ads_day": accounts.today() - 1})
    assert client.post("/v1/account/points/earn-ad", json={"key": "video-0010"}, headers=h(t)).json()["applied"] is True


def test_a_replayed_video_key_pays_once():
    t, _ = login()
    a = client.post("/v1/account/points/earn-ad", json={"key": "video-same"}, headers=h(t)).json()
    b = client.post("/v1/account/points/earn-ad", json={"key": "video-same"}, headers=h(t)).json()
    assert a["balance"] == 25 and b["balance"] == 25 and b["applied"] is False


def test_earned_points_stop_at_the_cap_but_never_take_points_away():
    t, _ = login(localBalance=98)
    assert client.post("/v1/account/points/earn-ad", json={"key": "video-cap1"}, headers=h(t)).json()["balance"] == 100
    set_days_back("ali@example.com", {"balance": 130})
    assert client.post("/v1/account/points/earn-ad", json={"key": "video-cap2"}, headers=h(t)).json()["balance"] == 130


# ------------------------------------------------------------------ pro by email ----------

def test_pro_granted_by_email_before_the_person_signs_in():
    assert admin("POST", "/v1/admin/grants", json={"email": " Ali@Example.com ", "plan": "yearly", "note": "tester"}).status_code == 200
    t, body = login()
    pro = body["account"]["pro"]
    assert pro["active"] is True and pro["plan"] == "yearly" and pro["endsAt"] > accounts.now_ms()


def test_pro_people_are_never_charged_points():
    t, _ = login()
    admin("POST", "/v1/admin/grants", json={"email": "ali@example.com", "plan": "lifetime"})
    r = client.post("/v1/account/points/spend", json={"key": "spend-pro01", "amount": 9}, headers=h(t)).json()
    assert r["proActive"] is True and r["applied"] is False and r["balance"] == 20
    assert client.get("/v1/account", headers=h(t)).json()["pro"]["endsAt"] is None  # lifetime


def test_revoking_pro_takes_effect_at_once_and_an_expired_grant_is_not_pro():
    t, _ = login()
    admin("POST", "/v1/admin/grants", json={"email": "ali@example.com", "plan": "monthly"})
    assert client.get("/v1/account", headers=h(t)).json()["pro"]["active"] is True
    assert admin("DELETE", "/v1/admin/grants/ali@example.com").json()["revoked"] is True
    assert client.get("/v1/account", headers=h(t)).json()["pro"]["active"] is False
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as conn:
        conn.execute("UPDATE pro_grants SET revoked_at = NULL, ends_at = %s", (accounts.now_ms() - 1000,))
    assert client.get("/v1/account", headers=h(t)).json()["pro"]["active"] is False


def test_granting_again_extends_a_running_grant_instead_of_resetting_it():
    first = admin("POST", "/v1/admin/grants", json={"email": "ali@example.com", "plan": "days", "days": 10}).json()
    second = admin("POST", "/v1/admin/grants", json={"email": "ali@example.com", "plan": "days", "days": 5}).json()
    assert second["endsAt"] - first["endsAt"] == 5 * accounts.DAY_MS


def test_days_plan_needs_a_number_of_days():
    assert admin("POST", "/v1/admin/grants", json={"email": "ali@example.com", "plan": "days"}).status_code == 400
    assert admin("POST", "/v1/admin/grants", json={"email": "not-an-email", "plan": "monthly"}).status_code == 400


# ------------------------------------------------------------------ admin ----------

def test_admin_needs_the_right_token_and_a_configured_one(monkeypatch):
    body = {"email": "ali@example.com", "plan": "monthly"}
    assert client.post("/v1/admin/grants", json=body).status_code == 401
    assert client.post("/v1/admin/grants", json=body, headers={"X-Admin-Token": "wrong-wrong-wrong-1"}).status_code == 401
    monkeypatch.setenv("ADMIN_TOKEN", "short")
    assert client.post("/v1/admin/grants", json=body, headers={"X-Admin-Token": "short"}).status_code == 503
    monkeypatch.delenv("ADMIN_TOKEN")
    assert client.post("/v1/admin/grants", json=body).status_code == 503


def test_admin_lists_accounts_and_grants_and_adjusts_points():
    login()
    login(sub="sub-2", email="sara@example.com", name="Sara")
    admin("POST", "/v1/admin/grants", json={"email": "sara@example.com", "plan": "monthly", "note": "gift"})
    accounts_list = admin("GET", "/v1/admin/accounts", params={"q": "sara"}).json()
    assert accounts_list["total"] == 2 and [a["email"] for a in accounts_list["accounts"]] == ["sara@example.com"]
    assert accounts_list["accounts"][0]["pro"] is True
    grants = admin("GET", "/v1/admin/grants").json()["grants"]
    assert grants[0]["email"] == "sara@example.com" and grants[0]["active"] is True and grants[0]["note"] == "gift"
    assert admin("POST", "/v1/admin/points", json={"email": "ali@example.com", "delta": 500}).status_code == 422
    assert admin("POST", "/v1/admin/points", json={"email": "ali@example.com", "delta": 100}).json()["balance"] == 100
    assert admin("POST", "/v1/admin/points", json={"email": "ali@example.com", "delta": -100}).json()["balance"] == 0
    assert admin("POST", "/v1/admin/points", json={"email": "nobody@example.com", "delta": 1}).status_code == 404


def test_admin_page_is_served_without_secrets():
    r = client.get("/admin")
    assert r.status_code == 200 and "X-Admin-Token" in r.text and ADMIN not in r.text


# ------------------------------------------------------------------ deleting ----------

def test_deleting_the_account_removes_everything_and_the_session():
    t, _ = login()
    client.post("/v1/account/points/spend", json={"key": "spend-del01", "amount": 2}, headers=h(t))
    assert client.delete("/v1/account", headers=h(t)).json() == {"deleted": True}
    assert client.get("/v1/account", headers=h(t)).status_code == 401
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        for table in ("accounts", "account_sessions", "points_ledger"):
            assert conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0
    # signing in again starts a brand-new account
    assert sign_in().json()["created"] is True


def test_family_sync_still_works_next_to_accounts():
    r = client.post(f"/v2/families/123456/register", json={"deviceId": "dev-" + uuid.uuid4().hex})
    assert r.status_code == 201
