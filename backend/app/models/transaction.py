from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import BigInteger, Boolean, DateTime, Float, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.crypto import EncryptedString
from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin

if TYPE_CHECKING:
    from app.models.account import BankAccount
    from app.models.user import User


class Transaction(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "transactions"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    account_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("bank_accounts.id", ondelete="CASCADE"), index=True
    )
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    direction: Mapped[str] = mapped_column(String(8), default="debit")  # "debit" | "credit"

    # Original currency amount in integer minor units (e.g. paise / cents)
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3), default="INR")

    # Converted into user's base currency in integer minor units
    amount_base_minor: Mapped[int] = mapped_column(BigInteger)
    fx_rate: Mapped[float] = mapped_column(Float, default=1.0)

    # Raw bank narration encrypted at rest
    raw_description: Mapped[str] = mapped_column(EncryptedString)
    # Canonical cleaned merchant name
    merchant_name: Mapped[str] = mapped_column(String(160), index=True)

    category: Mapped[str] = mapped_column(String(48), index=True)
    category_source: Mapped[str] = mapped_column(
        String(16), default="rule"
    )  # "user" | "ml" | "rule"
    category_confidence: Mapped[float] = mapped_column(Float, default=0.9)

    is_recurring: Mapped[bool] = mapped_column(Boolean, default=False)
    is_anomaly: Mapped[bool] = mapped_column(Boolean, default=False)
    anomaly_score: Mapped[float] = mapped_column(Float, default=0.0)
    anomaly_reason: Mapped[str | None] = mapped_column(String(255))

    external_id: Mapped[str | None] = mapped_column(String(128), index=True)

    user: Mapped[User] = relationship()
    account: Mapped[BankAccount] = relationship(back_populates="transactions")
