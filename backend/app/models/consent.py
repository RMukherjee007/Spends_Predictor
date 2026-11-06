from __future__ import annotations

import enum
import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, UUIDPrimaryKeyMixin

if TYPE_CHECKING:
    from app.models.user import User


class ConsentPurpose(enum.StrEnum):
    TERMS = "terms"
    PRIVACY_POLICY = "privacy_policy"
    ML_TRAINING = "ml_training"  # use anonymized data to train the global model
    PRODUCT_EMAILS = "product_emails"


class Consent(UUIDPrimaryKeyMixin, Base):
    """Append-only consent ledger (GDPR / DPDP). The latest row per purpose is authoritative."""

    __tablename__ = "consents"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    purpose: Mapped[str] = mapped_column(String(32))
    granted: Mapped[bool] = mapped_column(Boolean)
    policy_version: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    user: Mapped[User] = relationship(back_populates="consents")
