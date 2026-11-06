from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Body, Request, Response, status

from app.api.cookies import ACCESS_COOKIE, REFRESH_COOKIE, clear_auth_cookies, set_auth_cookies
from app.api.deps import SessionDep, SettingsDep, enforce_csrf
from app.core.errors import AuthenticationError, NotFoundError
from app.core.security import (
    InvalidTokenError,
    check_rate_limit,
    decode_access_token,
    revoke_access_jti,
)
from app.schemas.auth import (
    AuthResponse,
    DevLoginRequest,
    GoogleLoginRequest,
    RefreshRequest,
    TokenPair,
)
from app.schemas.common import ErrorResponse
from app.schemas.user import UserRead
from app.services import auth as auth_service

router = APIRouter(
    prefix="/auth",
    tags=["auth"],
    responses={401: {"model": ErrorResponse}, 403: {"model": ErrorResponse}},
)


def _build_response(
    response: Response,
    user: UserRead,
    tokens: auth_service.IssuedTokens,
    client: str,
    settings: SettingsDep,
) -> AuthResponse:
    if client == "web":
        set_auth_cookies(response, tokens, settings)
        return AuthResponse(user=user)
    return AuthResponse(
        user=user,
        tokens=TokenPair(
            access_token=tokens.access_token,
            refresh_token=tokens.refresh_token,
            expires_in=tokens.access_expires_in,
        ),
    )


@router.post("/google", response_model=AuthResponse, summary="Sign in with a Google ID token")
async def google_login(
    payload: GoogleLoginRequest,
    request: Request,
    response: Response,
    session: SessionDep,
    settings: SettingsDep,
) -> AuthResponse:
    client_ip = request.client.host if request.client else "local"
    check_rate_limit("auth", client_ip, limit=20, window_seconds=60.0)
    identity = await auth_service.verify_google_id_token(payload.id_token, settings)
    user = await auth_service.get_or_create_user(session, identity, settings, provider="google")
    tokens, _ = await auth_service.issue_tokens(
        session,
        user,
        settings,
        client=payload.client,
        user_agent=request.headers.get("user-agent"),
    )
    await session.commit()
    return _build_response(
        response, UserRead.model_validate(user), tokens, payload.client, settings
    )


@router.post(
    "/dev-login",
    response_model=AuthResponse,
    summary="Development-only passwordless login",
    include_in_schema=False,
)
async def dev_login(
    payload: DevLoginRequest,
    request: Request,
    response: Response,
    session: SessionDep,
    settings: SettingsDep,
) -> AuthResponse:
    if not settings.dev_login_enabled or settings.environment in ("staging", "production"):
        raise NotFoundError()
    client_ip = request.client.host if request.client else "local"
    check_rate_limit("auth", client_ip, limit=20, window_seconds=60.0)
    identity = auth_service.ExternalIdentity(
        subject=f"dev:{payload.email}",
        email=payload.email.lower(),
        full_name=payload.full_name,
        avatar_url=None,
    )
    user = await auth_service.get_or_create_user(session, identity, settings, provider="dev")
    tokens, _ = await auth_service.issue_tokens(
        session,
        user,
        settings,
        client=payload.client,
        user_agent=request.headers.get("user-agent"),
    )
    await session.commit()
    return _build_response(
        response, UserRead.model_validate(user), tokens, payload.client, settings
    )


@router.post("/refresh", response_model=AuthResponse, summary="Rotate the refresh token")
async def refresh(
    request: Request,
    response: Response,
    session: SessionDep,
    settings: SettingsDep,
    payload: Annotated[RefreshRequest | None, Body()] = None,
) -> AuthResponse:
    client_ip = request.client.host if request.client else "local"
    check_rate_limit("auth_refresh", client_ip, limit=30, window_seconds=60.0)
    if payload and payload.refresh_token:
        refresh_plain, client = payload.refresh_token, "mobile"
    elif cookie := request.cookies.get(REFRESH_COOKIE):
        enforce_csrf(request)
        refresh_plain, client = cookie, "web"
    else:
        raise AuthenticationError("Missing refresh token")

    try:
        user, tokens = await auth_service.rotate_refresh_token(
            session, refresh_plain, settings, user_agent=request.headers.get("user-agent")
        )
    except AuthenticationError:
        if client == "web":
            clear_auth_cookies(response, settings)
        raise
    await session.commit()
    return _build_response(response, UserRead.model_validate(user), tokens, client, settings)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT, summary="Sign out")
async def logout(
    request: Request,
    response: Response,
    session: SessionDep,
    settings: SettingsDep,
    payload: Annotated[RefreshRequest | None, Body()] = None,
) -> Response:
    refresh_plain = payload.refresh_token if payload else None
    if refresh_plain is None and (cookie := request.cookies.get(REFRESH_COOKIE)):
        enforce_csrf(request)
        refresh_plain = cookie
    if refresh_plain:
        await auth_service.revoke_refresh_token(session, refresh_plain)

    auth_header = request.headers.get("authorization", "")
    access_plain = (
        auth_header.removeprefix("Bearer ").strip()
        if auth_header.startswith("Bearer ")
        else request.cookies.get(ACCESS_COOKIE)
    )
    if access_plain:
        try:
            claims = decode_access_token(access_plain, settings)
            revoke_access_jti(claims.jti)
            if claims.family_id is not None:
                await auth_service.revoke_family(session, claims.family_id)
        except InvalidTokenError:
            pass

    await session.commit()
    response.status_code = status.HTTP_204_NO_CONTENT
    response.headers["Clear-Site-Data"] = '"cookies", "storage"'
    clear_auth_cookies(response, settings)
    return response
