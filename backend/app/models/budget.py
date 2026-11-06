from __future__ import annotations

import uuid

from sqlalchemy import BigInteger, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class Budget(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """Monthly category or total budget in the user's base currency minor units."""

    __tablename__ = "budgets"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    category: Mapped[str] = mapped_column(String(48))  # "total" or category slug
    monthly_limit_minor: Mapped[int] = mapped_column(BigInteger)
    alert_threshold_pct: Mapped[int] = mapped_column(Integer, default=80)
