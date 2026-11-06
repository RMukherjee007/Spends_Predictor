from __future__ import annotations

from fastapi import APIRouter

from app.api.deps import CurrentUser, SessionDep, SettingsDep
from app.core.errors import NotFoundError
from app.models.account import BankAccount
from app.schemas.finance import BankAccountRead
from app.services import finance as finance_service
from app.services.fx import convert_minor_units

router = APIRouter(prefix="/accounts", tags=["accounts"])


def _serialize_account(acct: BankAccount, base_currency: str) -> BankAccountRead:
    dto = BankAccountRead.model_validate(acct)
    dto.balance_base_minor = convert_minor_units(acct.balance_minor, acct.currency, base_currency)[0]
    return dto


@router.get("", response_model=list[BankAccountRead], summary="List connected bank accounts")
async def list_accounts(user: CurrentUser, session: SessionDep) -> list[BankAccountRead]:
    accts = await finance_service.list_accounts(session, user.id)
    return [_serialize_account(a, user.base_currency) for a in accts]


@router.post(
    "/sync-mock",
    response_model=list[BankAccountRead],
    summary="Connect/sync sandbox bank accounts with 6-month transaction history",
)
async def sync_mock_accounts(
    user: CurrentUser,
    session: SessionDep,
    settings: SettingsDep,
) -> list[BankAccountRead]:
    if settings.environment == "production" and not settings.dev_login_enabled:
        raise NotFoundError()
    accts = await finance_service.sync_mock_bank_data(session, user)
    return [_serialize_account(a, user.base_currency) for a in accts]
