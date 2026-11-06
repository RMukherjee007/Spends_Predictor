from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, SessionDep
from app.models.transaction import Transaction
from app.schemas.common import ErrorResponse
from app.schemas.user import UserRead, UserUpdate
from app.services.fx import convert_minor_units

router = APIRouter(prefix="/users", tags=["users"], responses={401: {"model": ErrorResponse}})


@router.get("/me", response_model=UserRead, summary="Current user's profile")
async def read_me(user: CurrentUser) -> UserRead:
    return UserRead.model_validate(user)


@router.patch("/me", response_model=UserRead, summary="Update profile & preferences")
async def update_me(payload: UserUpdate, user: CurrentUser, session: SessionDep) -> UserRead:
    old_currency = user.base_currency
    for field, value in payload.model_dump(exclude_unset=True).items():
        if field == "full_name" or value is not None:
            setattr(user, field, value)
    if user.base_currency != old_currency:
        txns = list(
            (
                await session.scalars(
                    select(Transaction).where(Transaction.user_id == user.id)
                )
            ).all()
        )
        for t in txns:
            base_minor, fx = convert_minor_units(t.amount_minor, t.currency, user.base_currency)
            t.amount_base_minor = base_minor
            t.fx_rate = fx
    await session.commit()
    await session.refresh(user)
    return UserRead.model_validate(user)
