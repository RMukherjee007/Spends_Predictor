from __future__ import annotations

from httpx import AsyncClient

from app.api.cookies import ACCESS_COOKIE, CSRF_COOKIE, CSRF_HEADER, REFRESH_COOKIE


async def web_login(client: AsyncClient, token: str = "good-token") -> str:
    """Log in as a web client; returns the CSRF token to echo on unsafe requests."""
    resp = await client.post("/api/v1/auth/google", json={"id_token": token, "client": "web"})
    assert resp.status_code == 200, resp.text
    return client.cookies[CSRF_COOKIE]


async def test_web_login_sets_cookies_and_no_tokens_in_body(
    client: AsyncClient, fake_google: object
) -> None:
    resp = await client.post("/api/v1/auth/google", json={"id_token": "good-token"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["tokens"] is None
    assert body["user"]["email"] == "asha@example.com"
    assert body["user"]["base_currency"] == "INR"

    set_cookie = resp.headers.get_list("set-cookie")
    access = next(c for c in set_cookie if c.startswith(f"{ACCESS_COOKIE}="))
    refresh = next(c for c in set_cookie if c.startswith(f"{REFRESH_COOKIE}="))
    assert "HttpOnly" in access and "Path=/api" in access
    assert "HttpOnly" in refresh and "Path=/api/v1/auth" in refresh


async def test_invalid_google_token_rejected(client: AsyncClient, fake_google: object) -> None:
    resp = await client.post("/api/v1/auth/google", json={"id_token": "forged"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "not_authenticated"
    assert resp.json()["error"]["request_id"]


async def test_me_requires_auth(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/users/me")
    assert resp.status_code == 401


async def test_web_session_can_read_me(client: AsyncClient, fake_google: object) -> None:
    await web_login(client)
    resp = await client.get("/api/v1/users/me")
    assert resp.status_code == 200
    assert resp.json()["full_name"] == "Asha Rao"


async def test_cookie_mutation_requires_csrf(client: AsyncClient, fake_google: object) -> None:
    csrf = await web_login(client)

    resp = await client.patch("/api/v1/users/me", json={"base_currency": "usd"})
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "csrf_failed"

    resp = await client.patch(
        "/api/v1/users/me", json={"base_currency": "usd"}, headers={CSRF_HEADER: csrf}
    )
    assert resp.status_code == 200
    assert resp.json()["base_currency"] == "USD"


async def test_mobile_login_returns_bearer_tokens(client: AsyncClient, fake_google: object) -> None:
    resp = await client.post(
        "/api/v1/auth/google", json={"id_token": "good-token", "client": "mobile"}
    )
    assert resp.status_code == 200
    tokens = resp.json()["tokens"]
    assert tokens["token_type"] == "bearer"
    assert 0 < tokens["expires_in"] <= 15 * 60
    assert ACCESS_COOKIE not in resp.cookies

    client.cookies.clear()
    me = await client.get(
        "/api/v1/users/me", headers={"Authorization": f"Bearer {tokens['access_token']}"}
    )
    assert me.status_code == 200

    # Bearer clients are not subject to CSRF checks.
    patched = await client.patch(
        "/api/v1/users/me",
        json={"timezone": "Europe/London"},
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )
    assert patched.status_code == 200
    assert patched.json()["timezone"] == "Europe/London"


async def test_refresh_rotation_and_reuse_detection(
    client: AsyncClient, fake_google: object
) -> None:
    resp = await client.post(
        "/api/v1/auth/google", json={"id_token": "good-token", "client": "mobile"}
    )
    first = resp.json()["tokens"]["refresh_token"]

    rotated = await client.post("/api/v1/auth/refresh", json={"refresh_token": first})
    assert rotated.status_code == 200
    second = rotated.json()["tokens"]["refresh_token"]
    assert second != first

    # Replaying the old token is treated as theft...
    replay = await client.post("/api/v1/auth/refresh", json={"refresh_token": first})
    assert replay.status_code == 401
    # ...and revokes the entire family, including the newest token.
    after = await client.post("/api/v1/auth/refresh", json={"refresh_token": second})
    assert after.status_code == 401


async def test_web_refresh_requires_csrf_and_rotates_cookie(
    client: AsyncClient, fake_google: object
) -> None:
    csrf = await web_login(client)
    old_refresh = client.cookies[REFRESH_COOKIE]

    denied = await client.post("/api/v1/auth/refresh")
    assert denied.status_code == 403

    ok = await client.post("/api/v1/auth/refresh", headers={CSRF_HEADER: csrf})
    assert ok.status_code == 200
    assert client.cookies[REFRESH_COOKIE] != old_refresh


async def test_logout_revokes_refresh_token(client: AsyncClient, fake_google: object) -> None:
    resp = await client.post(
        "/api/v1/auth/google", json={"id_token": "good-token", "client": "mobile"}
    )
    refresh = resp.json()["tokens"]["refresh_token"]
    out = await client.post("/api/v1/auth/logout", json={"refresh_token": refresh})
    assert out.status_code == 204
    again = await client.post("/api/v1/auth/refresh", json={"refresh_token": refresh})
    assert again.status_code == 401


async def test_same_email_links_to_existing_user(client: AsyncClient, fake_google: object) -> None:
    dev = await client.post(
        "/api/v1/auth/dev-login", json={"email": "Asha@Example.com", "client": "mobile"}
    )
    assert dev.status_code == 200
    google = await client.post(
        "/api/v1/auth/google", json={"id_token": "good-token", "client": "mobile"}
    )
    assert google.json()["user"]["id"] == dev.json()["user"]["id"]
