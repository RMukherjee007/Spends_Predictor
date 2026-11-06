"""Authentication: Google identity verification, user provisioning, token issuance & rotation."""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from google.auth.exceptions import GoogleAuthError
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.concurrency import run_in_threadpool

from app.core.config import Settings
from app.core.errors import AuthenticationError
from app.core.logging import get_logger
from app.core.security import (
    create_access_token,
    generate_refresh_token,
    hash_token,
    revoke_token_family_local,
)
from app.models import RefreshToken, User

logger = get_logger(__name__)

_GOOGLE_ISSUERS = {"accounts.google.com", "https://accounts.google.com"}
_google_transport = google_requests.Request()
_refresh_lock = asyncio.Lock()


@dataclass(frozen=True, slots=True)
class ExternalIdentity:
    subject: str
    email: str
    full_name: str | None
    avatar_url: str | None


@dataclass(frozen=True, slots=True)
class IssuedTokens:
    access_token: str
    access_expires_at: datetime
    refresh_token: str
    refresh_expires_at: datetime

    @property
    def access_expires_in(self) -> int:
        return max(0, int((self.access_expires_at - datetime.now(UTC)).total_seconds()))


def _as_utc(value: datetime) -> datetime:
    # SQLite drops tzinfo; Postgres keeps it. Normalise for comparisons.
    return value if value.tzinfo else value.replace(tzinfo=UTC)


async def verify_google_id_token(token: str, settings: Settings) -> ExternalIdentity:
    """Verify a Google ID token's signature, expiry, issuer and audience."""
    if not settings.google_client_ids:
        raise AuthenticationError("Google sign-in is not configured")
    try:
        claims: dict[str, Any] = await run_in_threadpool(
            google_id_token.verify_oauth2_token,
            token,
            _google_transport,
            settings.google_client_ids,
        )
    except (ValueError, GoogleAuthError) as exc:
        logger.info("auth.google.invalid_token", reason=str(exc))
        raise AuthenticationError("Invalid Google credential") from exc

    if claims.get("iss") not in _GOOGLE_ISSUERS:
        raise AuthenticationError("Invalid Google credential")
    if not claims.get("email") or not claims.get("email_verified"):
        raise AuthenticationError("Google account email is not verified")

    return ExternalIdentity(
        subject=str(claims["sub"]),
        email=str(claims["email"]).lower(),
        full_name=claims.get("name"),
        avatar_url=claims.get("picture"),
    )


async def get_or_create_user(
    session: AsyncSession, identity: ExternalIdentity, settings: Settings, *, provider: str
) -> User:
    user: User | None = None
    if provider == "google":
        user = await session.scalar(select(User).where(User.google_sub == identity.subject))
    if user is None:
        # Link to an existing account with the same (verified) email.
        user = await session.scalar(select(User).where(User.email == identity.email))

    if user is None:
        user = User(
            email=identity.email,
            full_name=identity.full_name,
            avatar_url=identity.avatar_url,
            base_currency=settings.default_currency,
        )
        session.add(user)
        logger.info("auth.user.created", provider=provider)

    if provider == "google":
        user.google_sub = identity.subject
    if identity.avatar_url and not user.avatar_url:
        user.avatar_url = identity.avatar_url
    if identity.full_name and (provider == "dev" or not user.full_name):
        user.full_name = identity.full_name

    user.last_login_at = datetime.now(UTC)
    await session.flush()

    if not user.is_active:
        raise AuthenticationError("This account has been deactivated")

    return user


async def issue_tokens(
    session: AsyncSession,
    user: User,
    settings: Settings,
    *,
    client: str,
    user_agent: str | None,
    family_id: uuid.UUID | None = None,
) -> tuple[IssuedTokens, RefreshToken]:
    fid = family_id or uuid.uuid4()
    access_token, access_exp = create_access_token(user.id, settings, family_id=fid)
    refresh_plain = generate_refresh_token()
    refresh_exp = datetime.now(UTC) + timedelta(days=settings.refresh_token_ttl_days)
    record = RefreshToken(
        user_id=user.id,
        token_hash=hash_token(refresh_plain),
        family_id=fid,
        client=client,
        user_agent=(user_agent or "")[:512] or None,
        expires_at=refresh_exp,
    )
    session.add(record)
    await session.flush()
    return IssuedTokens(access_token, access_exp, refresh_plain, refresh_exp), record


async def rotate_refresh_token(
    session: AsyncSession, refresh_plain: str, settings: Settings, *, user_agent: str | None
) -> tuple[User, IssuedTokens]:
    async with _refresh_lock:
        record = await session.scalar(
            select(RefreshToken).where(RefreshToken.token_hash == hash_token(refresh_plain))
        )
        if record is None:
            raise AuthenticationError("Invalid refresh token")

        now = datetime.now(UTC)
        if record.revoked_at is not None:
            # Re-use of a rotated token: assume theft and kill the whole session family.
            await revoke_family(session, record.family_id)
            await session.commit()
            logger.warning("auth.refresh.reuse_detected", user_id=str(record.user_id))
            raise AuthenticationError("Refresh token has been revoked")
        if _as_utc(record.expires_at) <= now:
            raise AuthenticationError("Refresh token has expired")

        user = await session.get(User, record.user_id)
        if user is None or not user.is_active:
            raise AuthenticationError("Invalid refresh token")

        # Atomically claim the unrevoked refresh token before minting a replacement
        claim_res = await session.execute(
            update(RefreshToken)
            .where(RefreshToken.id == record.id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        if getattr(claim_res, "rowcount", 1) == 0:
            await revoke_family(session, record.family_id)
            await session.commit()
            raise AuthenticationError("Refresh token has been revoked")

        tokens, new_record = await issue_tokens(
            session,
            user,
            settings,
            client=record.client,
            user_agent=user_agent,
            family_id=record.family_id,
        )
        record.revoked_at = now
        record.replaced_by_id = new_record.id
        await session.flush()
        return user, tokens


async def revoke_refresh_token(session: AsyncSession, refresh_plain: str) -> None:
    record = await session.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_token(refresh_plain))
    )
    if record is not None:
        revoke_token_family_local(record.family_id)
    await session.execute(
        update(RefreshToken)
        .where(
            RefreshToken.token_hash == hash_token(refresh_plain),
            RefreshToken.revoked_at.is_(None),
        )
        .values(revoked_at=datetime.now(UTC))
    )


async def revoke_family(session: AsyncSession, family_id: uuid.UUID) -> None:
    revoke_token_family_local(family_id)
    await session.execute(
        update(RefreshToken)
        .where(RefreshToken.family_id == family_id, RefreshToken.revoked_at.is_(None))
        .values(revoked_at=datetime.now(UTC))
    )
