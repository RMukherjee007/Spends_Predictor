from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import BigInteger, DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.crypto import EncryptedString
from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin

if TYPE_CHECKING:
    from app.models.transaction import Transaction
    from app.models.user import User


class BankAccount(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "bank_accounts"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(120))
    institution: Mapped[str] = mapped_column(String(120))
    account_type: Mapped[str] = mapped_column(String(32), default="checking")
    currency: Mapped[str] = mapped_column(String(3), default="INR")
    balance_minor: Mapped[int] = mapped_column(BigInteger, default=0)

    # Encrypted at rest using MultiFernet
    masked_number: Mapped[str | None] = mapped_column(EncryptedString)
    provider: Mapped[str] = mapped_column(String(32), default="mock_aa")
    external_id: Mapped[str | None] = mapped_column(String(128), index=True)
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User] = relationship()
    transactions: Mapped[list[Transaction]] = relationship(
        back_populates="account", cascade="all, delete-orphan", passive_deletes=True
    )
