from __future__ import annotations

import pytest
from httpx import AsyncClient
from pydantic import ValidationError

from app.core.config import Settings
from app.core.crypto import decrypt_str, encrypt_str
from app.core.currencies import is_valid_currency, minor_units


async def test_liveness(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    assert resp.headers["x-request-id"]
    assert resp.headers["x-content-type-options"] == "nosniff"


async def test_readiness_checks_database(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/health/ready")
    assert resp.status_code == 200
    assert resp.json()["checks"]["database"] == "ok"


async def test_request_id_is_propagated(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/health", headers={"X-Request-ID": "abc-123"})
    assert resp.headers["x-request-id"] == "abc-123"


async def test_unknown_route_uses_error_envelope(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/nope")
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


async def test_validation_errors_use_error_envelope(client: AsyncClient) -> None:
    resp = await client.post("/api/v1/auth/google", json={})
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation_error"


async def test_currencies_endpoint(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/meta/currencies")
    assert resp.status_code == 200
    codes = {c["code"]: c["minor_units"] for c in resp.json()}
    assert codes["INR"] == 2 and codes["JPY"] == 0


def test_encryption_roundtrip() -> None:
    token = encrypt_str("XXXX-1234")
    assert token != "XXXX-1234"
    assert decrypt_str(token) == "XXXX-1234"


def test_currency_metadata() -> None:
    assert is_valid_currency("inr")
    assert not is_valid_currency("ABC")
    assert minor_units("JPY") == 0
    assert minor_units("KWD") == 3


def test_production_rejects_insecure_defaults() -> None:
    with pytest.raises(ValidationError) as exc:
        Settings(environment="production", dev_login_enabled=True, _env_file=None)
    message = str(exc.value)
    assert "JWT_SECRET" in message
    assert "DEV_LOGIN_ENABLED" in message
    assert "FIELD_ENCRYPTION_KEYS" in message


def test_list_settings_accept_comma_separated() -> None:
    settings = Settings(cors_origins="https://a.com, https://b.com", _env_file=None)
    assert settings.cors_origins == ["https://a.com", "https://b.com"]


def test_render_database_url_normalized_to_asyncpg() -> None:
    s1 = Settings(database_url="postgres://u:p@host:5432/db", _env_file=None)
    s2 = Settings(database_url="postgresql://u:p@host:5432/db", _env_file=None)
    assert s1.database_url == "postgresql+asyncpg://u:p@host:5432/db"
    assert s2.database_url == "postgresql+asyncpg://u:p@host:5432/db"
