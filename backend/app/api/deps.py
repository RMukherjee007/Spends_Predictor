"""Shared FastAPI dependencies."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.cookies import ACCESS_COOKIE, CSRF_COOKIE, CSRF_HEADER
from app.core.config import Settings, get_settings
from app.core.errors import AuthenticationError, CSRFError, PermissionDeniedError
from app.core.security import InvalidTokenError, decode_access_token, tokens_match
from app.db.session import get_session
from app.models import RefreshToken, User

SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})

_bearer = HTTPBearer(auto_error=False, description="Access token (mobile / API clients)")

SettingsDep = Annotated[Settings, Depends(get_settings)]
SessionDep = Annotated[AsyncSession, Depends(get_session)]


def enforce_csrf(request: Request) -> None:
    """Double-submit check for cookie-authenticated, state-changing requests."""
    if request.method in SAFE_METHODS:
        return
    if not tokens_match(request.headers.get(CSRF_HEADER), request.cookies.get(CSRF_COOKIE)):
        raise CSRFError("Missing or invalid CSRF token")


async def get_current_user(
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    bearer: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> User:
    if bearer is not None:
        token = bearer.credentials
        request.state.auth_method = "bearer"
    elif cookie_token := request.cookies.get(ACCESS_COOKIE):
        token = cookie_token
        request.state.auth_method = "cookie"
        enforce_csrf(request)
    else:
        raise AuthenticationError()

    try:
        claims = decode_access_token(token, settings)
    except InvalidTokenError as exc:
        raise AuthenticationError("Invalid or expired access token") from exc

    if claims.family_id is not None:
        active_family = await session.scalar(
            select(RefreshToken.id)
            .where(
                RefreshToken.family_id == claims.family_id,
                RefreshToken.revoked_at.is_(None),
            )
            .limit(1)
        )
        if active_family is None:
            raise AuthenticationError("Session has been revoked")

    user = await session.get(User, claims.user_id)
    if user is None or not user.is_active:
        raise AuthenticationError("Invalid or expired access token")
    request.state.user_id = str(user.id)
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


async def require_admin(user: CurrentUser) -> User:
    if not user.is_admin:
        raise PermissionDeniedError()
    return user


AdminUser = Annotated[User, Depends(require_admin)]
