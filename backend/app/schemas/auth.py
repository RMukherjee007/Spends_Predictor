from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, EmailStr, Field

from app.schemas.user import UserRead

ClientType = Literal["web", "mobile"]


class GoogleLoginRequest(BaseModel):
    id_token: str = Field(min_length=1, max_length=4096)
    client: ClientType = "web"


class DevLoginRequest(BaseModel):
    email: EmailStr
    full_name: str | None = Field(default=None, max_length=200)
    client: ClientType = "web"


class RefreshRequest(BaseModel):
    """Mobile clients send the refresh token in the body; web clients rely on the cookie."""

    refresh_token: str | None = Field(default=None, max_length=256)


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: Literal["bearer"] = "bearer"  # noqa: S105
    expires_in: int = Field(description="Access token lifetime in seconds")


class AuthResponse(BaseModel):
    user: UserRead
    # Only populated for mobile clients. Web clients receive httpOnly cookies instead.
    tokens: TokenPair | None = None
