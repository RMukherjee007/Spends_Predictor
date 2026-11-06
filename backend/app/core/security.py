"""Token primitives: short-lived JWT access tokens, opaque rotating refresh tokens, CSRF tokens."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt

from app.core.config import Settings
from app.core.errors import RateLimitError

ACCESS_TOKEN_TYPE = "access"  # noqa: S105

_REVOKED_JTIS: set[str] = set()
_REVOKED_FAMILIES: set[uuid.UUID] = set()
_RATE_BUCKETS: dict[str, deque[float]] = defaultdict(deque)


class InvalidTokenError(Exception):
    """Raised when a token is malformed, expired, or otherwise not acceptable."""


@dataclass(frozen=True, slots=True)
class AccessTokenClaims:
    user_id: uuid.UUID
    expires_at: datetime
    jti: str = ""
    family_id: uuid.UUID | None = None


def create_access_token(
    user_id: uuid.UUID,
    settings: Settings,
    *,
    family_id: uuid.UUID | None = None,
) -> tuple[str, datetime]:
    now = datetime.now(UTC)
    expires_at = now + timedelta(minutes=settings.access_token_ttl_minutes)
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "type": ACCESS_TOKEN_TYPE,
        "iss": settings.jwt_issuer,
        "iat": now,
        "exp": expires_at,
        "jti": secrets.token_hex(8),
    }
    if family_id is not None:
        payload["fid"] = str(family_id)
    token = jwt.encode(
        payload, settings.jwt_secret.get_secret_value(), algorithm=settings.jwt_algorithm
    )
    return token, expires_at


def decode_access_token(token: str, settings: Settings) -> AccessTokenClaims:
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret.get_secret_value(),
            algorithms=[settings.jwt_algorithm],
            issuer=settings.jwt_issuer,
            options={"require": ["sub", "exp", "iat", "iss", "type"]},
        )
    except jwt.PyJWTError as exc:
        raise InvalidTokenError(str(exc)) from exc
    if payload.get("type") != ACCESS_TOKEN_TYPE:
        raise InvalidTokenError("wrong token type")
    try:
        user_id = uuid.UUID(payload["sub"])
    except ValueError as exc:
        raise InvalidTokenError("invalid subject") from exc

    jti = str(payload.get("jti", ""))
    fid_raw = payload.get("fid")
    family_id: uuid.UUID | None = None
    if fid_raw:
        try:
            family_id = uuid.UUID(str(fid_raw))
        except ValueError:
            family_id = None

    if (jti and jti in _REVOKED_JTIS) or (family_id and family_id in _REVOKED_FAMILIES):
        raise InvalidTokenError("access token has been revoked")

    return AccessTokenClaims(
        user_id=user_id,
        expires_at=datetime.fromtimestamp(payload["exp"], tz=UTC),
        jti=jti,
        family_id=family_id,
    )


def revoke_access_jti(jti: str) -> None:
    if jti:
        _REVOKED_JTIS.add(jti)


def revoke_token_family_local(family_id: uuid.UUID) -> None:
    _REVOKED_FAMILIES.add(family_id)


def check_rate_limit(bucket: str, key: str, *, limit: int = 20, window_seconds: float = 60.0) -> None:
    """Sliding-window rate limiter per (bucket, client key)."""
    now = time.monotonic()
    q = _RATE_BUCKETS[f"{bucket}:{key}"]
    cutoff = now - window_seconds
    while q and q[0] < cutoff:
        q.popleft()
    if len(q) >= limit:
        raise RateLimitError("Too many requests; please slow down and try again shortly")
    q.append(now)


def generate_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def hash_token(token: str) -> str:
    """Refresh tokens are high-entropy random values, so a fast hash is sufficient."""
    return hashlib.sha256(token.encode()).hexdigest()


def generate_csrf_token() -> str:
    return secrets.token_urlsafe(32)


def tokens_match(a: str | None, b: str | None) -> bool:
    if not a or not b:
        return False
    return hmac.compare_digest(a.encode(), b.encode())
