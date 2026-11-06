from __future__ import annotations

from httpx import AsyncClient


async def _mobile_token(client: AsyncClient) -> str:
    resp = await client.post(
        "/api/v1/auth/dev-login", json={"email": "dev@example.com", "client": "mobile"}
    )
    assert resp.status_code == 200
    return str(resp.json()["tokens"]["access_token"])


async def test_rejects_unknown_currency(client: AsyncClient) -> None:
    token = await _mobile_token(client)
    resp = await client.patch(
        "/api/v1/users/me",
        json={"base_currency": "XYZ"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422


async def test_rejects_unknown_timezone(client: AsyncClient) -> None:
    token = await _mobile_token(client)
    resp = await client.patch(
        "/api/v1/users/me",
        json={"timezone": "Mars/Olympus"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422


async def test_rejects_unknown_fields(client: AsyncClient) -> None:
    token = await _mobile_token(client)
    resp = await client.patch(
        "/api/v1/users/me",
        json={"is_admin": True},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422


async def test_invalid_bearer_token(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/users/me", headers={"Authorization": "Bearer nonsense"})
    assert resp.status_code == 401


async def test_update_and_clear_full_name(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/auth/dev-login",
        json={"email": "name_test@example.com", "full_name": "Asha Rao", "client": "mobile"},
    )
    assert resp.status_code == 200
    assert resp.json()["user"]["full_name"] == "Asha Rao"
    token = str(resp.json()["tokens"]["access_token"])

    cleared = await client.patch(
        "/api/v1/users/me",
        json={"full_name": None},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert cleared.status_code == 200
    assert cleared.json()["full_name"] is None
