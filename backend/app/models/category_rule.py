from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class CategoryRule(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """Per-user active-learning override mapping a merchant pattern to a category."""

    __tablename__ = "category_rules"

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    merchant_pattern: Mapped[str] = mapped_column(String(160), index=True)
    category: Mapped[str] = mapped_column(String(48))
    hit_count: Mapped[int] = mapped_column(Integer, default=1)
