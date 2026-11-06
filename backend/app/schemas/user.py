from __future__ import annotations

import uuid
from datetime import datetime
from zoneinfo import available_timezones

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.core.currencies import is_valid_currency


class UserRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    email: str
    full_name: str | None
    avatar_url: str | None
    base_currency: str
    timezone: str
    locale: str
    is_admin: bool
    created_at: datetime


class UserUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    full_name: str | None = Field(default=None, min_length=1, max_length=200)
    base_currency: str | None = Field(default=None, min_length=3, max_length=3)
    timezone: str | None = Field(default=None, max_length=64)
    locale: str | None = Field(default=None, min_length=2, max_length=16, pattern=r"^[A-Za-z-]+$")

    @field_validator("base_currency")
    @classmethod
    def _currency(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.upper()
        if not is_valid_currency(value):
            raise ValueError("unsupported currency code")
        return value

    @field_validator("timezone")
    @classmethod
    def _timezone(cls, value: str | None) -> str | None:
        if value is not None and value not in available_timezones():
            raise ValueError("unknown IANA timezone")
        return value
