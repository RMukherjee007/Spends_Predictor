"""Auth cookie management for the web client.

* `sp_access`  - httpOnly JWT, sent to all /api routes.
* `sp_refresh` - httpOnly opaque token, only sent to /api/v1/auth.
* `sp_csrf`    - readable by JS; must be echoed in the `X-CSRF-Token` header on unsafe requests
                 (double-submit pattern).
"""

from __future__ import annotations

from fastapi import Response

from app.core.config import Settings
from app.core.security import generate_csrf_token
from app.services.auth import IssuedTokens

ACCESS_COOKIE = "sp_access"
REFRESH_COOKIE = "sp_refresh"
CSRF_COOKIE = "sp_csrf"
CSRF_HEADER = "X-CSRF-Token"


def _refresh_path(settings: Settings) -> str:
    return f"{settings.api_v1_prefix}/auth"


def set_auth_cookies(response: Response, tokens: IssuedTokens, settings: Settings) -> None:
    common = {"secure": settings.cookie_secure, "domain": settings.cookie_domain}
    response.set_cookie(
        ACCESS_COOKIE,
        tokens.access_token,
        max_age=settings.access_token_ttl_minutes * 60,
        httponly=True,
        samesite="lax",
        path="/api",
        **common,  # type: ignore[arg-type]
    )
    response.set_cookie(
        REFRESH_COOKIE,
        tokens.refresh_token,
        max_age=settings.refresh_token_ttl_days * 86400,
        httponly=True,
        samesite="strict",
        path=_refresh_path(settings),
        **common,  # type: ignore[arg-type]
    )
    response.set_cookie(
        CSRF_COOKIE,
        generate_csrf_token(),
        max_age=settings.refresh_token_ttl_days * 86400,
        httponly=False,
        samesite="strict",
        path="/",
        **common,  # type: ignore[arg-type]
    )


def clear_auth_cookies(response: Response, settings: Settings) -> None:
    common = {"secure": settings.cookie_secure, "domain": settings.cookie_domain}
    response.delete_cookie(ACCESS_COOKIE, path="/api", **common)  # type: ignore[arg-type]
    response.delete_cookie(REFRESH_COOKIE, path=_refresh_path(settings), **common)  # type: ignore[arg-type]
    response.delete_cookie(CSRF_COOKIE, path="/", **common)  # type: ignore[arg-type]
