"""Application settings, loaded from environment variables (and `.env` in development)."""

from __future__ import annotations

from functools import lru_cache
from typing import Annotated, Any, Literal

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

_INSECURE_DEV_SECRET = "dev-insecure-change-me-dev-insecure-change-me"  # noqa: S105

Environment = Literal["development", "test", "staging", "production"]


def _split_csv(value: Any) -> Any:
    """Allow list settings to be supplied as comma-separated env vars."""
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    return value


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # --- App ---
    app_name: str = "Spends Predictor"
    environment: Environment = "development"
    api_v1_prefix: str = "/api/v1"
    log_level: str = "INFO"
    default_currency: str = "INR"

    # --- Infrastructure ---
    database_url: str = "postgresql+asyncpg://spends:spends@localhost:5432/spends"
    database_pool_size: int = 5
    redis_url: str = "redis://localhost:6379/0"

    # Only needed when the frontend is served from a different origin. In production the
    # frontend proxies /api/* (Vercel rewrites) so requests are same-origin.
    cors_origins: Annotated[list[str], NoDecode] = ["http://localhost:5173"]

    # --- Auth ---
    jwt_secret: SecretStr = SecretStr(_INSECURE_DEV_SECRET)
    jwt_algorithm: str = "HS256"
    jwt_issuer: str = "spends-predictor"
    access_token_ttl_minutes: int = 15
    refresh_token_ttl_days: int = 30
    # Google OAuth client IDs accepted as ID-token audiences (web, iOS, Android).
    google_client_ids: Annotated[list[str], NoDecode] = []
    # Passwordless local login for development without Google credentials. Never in production.
    dev_login_enabled: bool = False

    # --- Cookies (web client) ---
    cookie_secure: bool = True
    cookie_domain: str | None = None

    # --- Encryption at rest (application-level) ---
    # Fernet keys, comma-separated. The first key encrypts; all keys decrypt (rotation).
    field_encryption_keys: Annotated[list[SecretStr], NoDecode] = []

    # --- Observability ---
    sentry_dsn: str | None = None
    sentry_traces_sample_rate: float = 0.1

    _split_lists = field_validator(
        "cors_origins", "google_client_ids", "field_encryption_keys", mode="before"
    )(_split_csv)

    @field_validator("default_currency")
    @classmethod
    def _upper_currency(cls, value: str) -> str:
        return value.upper()

    @field_validator("database_url")
    @classmethod
    def _normalize_database_url(cls, value: str) -> str:
        """Render/Heroku-style providers emit `postgres://`; SQLAlchemy asyncpg wants `postgresql+asyncpg://`."""
        if value.startswith("postgres://"):
            return "postgresql+asyncpg://" + value.removeprefix("postgres://")
        if value.startswith("postgresql://"):
            return "postgresql+asyncpg://" + value.removeprefix("postgresql://")
        return value

    @property
    def is_production(self) -> bool:
        return self.environment == "production"

    @property
    def docs_enabled(self) -> bool:
        return not self.is_production

    @model_validator(mode="after")
    def _validate_for_deployed_envs(self) -> Settings:
        if self.environment not in ("staging", "production"):
            return self
        problems: list[str] = []
        secret = self.jwt_secret.get_secret_value()
        if secret == _INSECURE_DEV_SECRET or len(secret) < 32:
            problems.append("JWT_SECRET must be set to a random value of at least 32 characters")
        if self.dev_login_enabled:
            problems.append("DEV_LOGIN_ENABLED must be false")
        if not self.field_encryption_keys:
            problems.append("FIELD_ENCRYPTION_KEYS must be set")
        if not self.google_client_ids:
            problems.append("GOOGLE_CLIENT_IDS must be set")
        if not self.cookie_secure:
            problems.append("COOKIE_SECURE must be true")
        if problems:
            raise ValueError(f"Invalid {self.environment} configuration: " + "; ".join(problems))
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
