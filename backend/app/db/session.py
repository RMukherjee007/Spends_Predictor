from __future__ import annotations

from collections.abc import AsyncIterator
from functools import lru_cache

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import get_settings


@lru_cache
def get_engine() -> AsyncEngine:
    settings = get_settings()
    kwargs: dict[str, object] = {"pool_pre_ping": True}
    if not settings.database_url.startswith("sqlite"):
        kwargs |= {"pool_size": settings.database_pool_size, "max_overflow": 10}
    return create_async_engine(settings.database_url, **kwargs)


@lru_cache
def get_sessionmaker() -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(get_engine(), expire_on_commit=False, autoflush=False)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency: one session per request, committed by the caller explicitly."""
    async with get_sessionmaker()() as session:
        yield session
