"""Shared test fixtures. Tests run against SQLite by default and Postgres in CI
(set TEST_DATABASE_URL)."""

from __future__ import annotations

import os

# Must be set before the app is imported.
os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("COOKIE_SECURE", "false")
os.environ.setdefault("GOOGLE_CLIENT_IDS", "test-client-id.apps.googleusercontent.com")
os.environ.setdefault("DEV_LOGIN_ENABLED", "true")
os.environ["DATABASE_URL"] = os.environ.get("TEST_DATABASE_URL", "sqlite+aiosqlite://")

from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import StaticPool

from app.core.config import get_settings
from app.core.errors import AuthenticationError
from app.core.security import _RATE_BUCKETS, _REVOKED_FAMILIES, _REVOKED_JTIS
from app.db.base import Base
from app.db.session import get_session
from app.main import create_app
from app.services import auth as auth_service


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    url = os.environ["DATABASE_URL"]
    kwargs: dict[str, object] = {}
    if url.startswith("sqlite"):
        kwargs = {"poolclass": StaticPool, "connect_args": {"check_same_thread": False}}
    engine = create_async_engine(url, **kwargs)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    yield async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest.fixture
async def client(
    session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncClient]:
    _RATE_BUCKETS.clear()
    _REVOKED_JTIS.clear()
    _REVOKED_FAMILIES.clear()
    app = create_app(get_settings())

    async def _override_session() -> AsyncIterator[AsyncSession]:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_session] = _override_session
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as http:
        yield http


@pytest.fixture
def fake_google(monkeypatch: pytest.MonkeyPatch) -> dict[str, auth_service.ExternalIdentity]:
    """Replace Google token verification. Maps token string -> identity."""
    identities: dict[str, auth_service.ExternalIdentity] = {
        "good-token": auth_service.ExternalIdentity(
            subject="google-sub-1",
            email="asha@example.com",
            full_name="Asha Rao",
            avatar_url="https://example.com/a.png",
        )
    }

    async def _verify(token: str, settings: object) -> auth_service.ExternalIdentity:
        if token not in identities:
            raise AuthenticationError("Invalid Google credential")
        return identities[token]

    monkeypatch.setattr(auth_service, "verify_google_id_token", _verify)
    return identities
