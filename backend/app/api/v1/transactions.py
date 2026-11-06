from __future__ import annotations

import uuid

from fastapi import APIRouter, Query

from app.api.deps import CurrentUser, SessionDep
from app.core.security import check_rate_limit
from app.schemas.finance import (
    CsvImportRequest,
    CsvImportResponse,
    TransactionRead,
    TransactionUpdateCategory,
)
from app.services import finance as finance_service

router = APIRouter(prefix="/transactions", tags=["transactions"])


@router.get("", response_model=list[TransactionRead], summary="List transactions with filters")
async def list_transactions(
    user: CurrentUser,
    session: SessionDep,
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    category: str | None = Query(default=None),
    account_id: uuid.UUID | None = Query(default=None),
) -> list[TransactionRead]:
    txns = await finance_service.list_transactions(
        session,
        user.id,
        limit=limit,
        offset=offset,
        category=category,
        account_id=account_id,
    )
    return [TransactionRead.model_validate(t) for t in txns]


@router.patch(
    "/{transaction_id}/category",
    response_model=TransactionRead,
    summary="Update transaction category (teaches active learning model)",
)
async def update_category(
    transaction_id: uuid.UUID,
    payload: TransactionUpdateCategory,
    user: CurrentUser,
    session: SessionDep,
) -> TransactionRead:
    txn = await finance_service.update_transaction_category(
        session,
        user.id,
        transaction_id,
        payload.category,
        apply_to_future=payload.apply_to_future,
    )
    return TransactionRead.model_validate(txn)


@router.post(
    "/import-csv",
    response_model=CsvImportResponse,
    summary="Import bank or credit card statement from CSV",
)
async def import_csv(
    payload: CsvImportRequest,
    user: CurrentUser,
    session: SessionDep,
) -> CsvImportResponse:
    check_rate_limit("csv_import", str(user.id), limit=15, window_seconds=60.0)
    imported, skipped = await finance_service.import_csv_statement(
        session, user, payload.account_id, payload.csv_content
    )
    return CsvImportResponse(imported_count=imported, skipped_count=skipped)
