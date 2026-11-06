"""Application-level field encryption for sensitive columns.

Uses MultiFernet (AES-128-CBC + HMAC-SHA256) so keys can be rotated: the first key encrypts,
every configured key can decrypt. Phase 3 can swap the key source for a cloud KMS (envelope
encryption) without changing the column type.
"""

from __future__ import annotations

import base64
import hashlib
from functools import lru_cache
from typing import Any

from cryptography.fernet import Fernet, InvalidToken, MultiFernet
from sqlalchemy import String, Text
from sqlalchemy.engine import Dialect
from sqlalchemy.types import TypeDecorator

from app.core.config import get_settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class DecryptionError(Exception):
    pass


def _dev_key() -> bytes:
    # Deterministic key for local development only; production config validation forbids this.
    return base64.urlsafe_b64encode(hashlib.sha256(b"spends-predictor-dev-only-key").digest())


@lru_cache
def get_cipher() -> MultiFernet:
    settings = get_settings()
    keys = [k.get_secret_value().encode() for k in settings.field_encryption_keys]
    if not keys:
        if settings.environment in ("staging", "production"):
            raise RuntimeError("FIELD_ENCRYPTION_KEYS is required")
        logger.warning("field_encryption.using_dev_key")
        keys = [_dev_key()]
    return MultiFernet([Fernet(k) for k in keys])


def encrypt_str(value: str) -> str:
    return get_cipher().encrypt(value.encode()).decode()


def decrypt_str(token: str) -> str:
    try:
        return get_cipher().decrypt(token.encode()).decode()
    except InvalidToken as exc:
        raise DecryptionError("unable to decrypt value") from exc


def generate_key() -> str:
    return Fernet.generate_key().decode()


class EncryptedString(TypeDecorator[str]):
    """A string column transparently encrypted at rest. Not searchable or sortable."""

    impl = Text
    cache_ok = True

    def load_dialect_impl(self, dialect: Dialect) -> Any:
        return dialect.type_descriptor(Text() if dialect.name != "sqlite" else String())

    def process_bind_param(self, value: str | None, dialect: Dialect) -> str | None:
        return None if value is None else encrypt_str(value)

    def process_result_value(self, value: str | None, dialect: Dialect) -> str | None:
        return None if value is None else decrypt_str(value)
