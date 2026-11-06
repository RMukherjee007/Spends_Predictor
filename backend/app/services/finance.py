from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import NotFoundError
from app.models.account import BankAccount
from app.models.category_rule import CategoryRule
from app.models.transaction import Transaction
from app.models.user import User
from app.schemas.finance import MLInsightsResponse
from app.services.categorizer import categorize_transaction
from app.services.connectors import MockBankConnector, RawTransactionPayload, parse_csv_statement
from app.services.fx import convert_minor_units
from app.services.ml_engine import TxnRecord, build_ml_insights_report


async def get_user_rules_map(session: AsyncSession, user_id: uuid.UUID) -> dict[str, str]:
    stmt = select(CategoryRule).where(CategoryRule.user_id == user_id)
    rules = (await session.scalars(stmt)).all()
    return {r.merchant_pattern: r.category for r in rules}


async def _get_existing_external_ids(session: AsyncSession, user_id: uuid.UUID) -> set[str]:
    rows = await session.scalars(
        select(Transaction.external_id).where(
            Transaction.user_id == user_id,
            Transaction.external_id.is_not(None),
        )
    )
    return {r for r in rows.all() if r is not None}


def _build_transaction(
    user: User,
    account_id: uuid.UUID,
    item: RawTransactionPayload,
    user_rules: dict[str, str],
) -> Transaction:
    base_minor, fx = convert_minor_units(item.amount_minor, item.currency, user.base_currency)
    cat_res = categorize_transaction(item.raw_description, user_rules)
    return Transaction(
        user_id=user.id,
        account_id=account_id,
        occurred_at=item.occurred_at,
        direction=item.direction,
        amount_minor=item.amount_minor,
        currency=item.currency,
        amount_base_minor=base_minor,
        fx_rate=fx,
        raw_description=item.raw_description,
        merchant_name=cat_res.merchant_name,
        category=cat_res.category,
        category_source=cat_res.source,
        category_confidence=cat_res.confidence,
        external_id=item.external_id,
    )


def _to_txn_record(t: Transaction) -> TxnRecord:
    return TxnRecord(
        id=str(t.id),
        occurred_at=t.occurred_at,
        direction=t.direction,
        amount_base_minor=t.amount_base_minor,
        merchant_name=t.merchant_name,
        category=t.category,
        category_source=t.category_source,
        category_confidence=t.category_confidence,
        amount_minor=t.amount_minor,
        currency=t.currency,
        fx_rate=t.fx_rate,
    )


async def sync_mock_bank_data(session: AsyncSession, user: User) -> list[BankAccount]:
    """Populate 6 months of realistic multi-account sandbox data for the user."""
    connector = MockBankConnector()
    raw_accounts, raw_txns = connector.fetch_accounts_and_transactions()
    user_rules = await get_user_rules_map(session, user.id)

    # Map external_id -> BankAccount
    account_map: dict[str, BankAccount] = {}
    for a in raw_accounts:
        stmt = select(BankAccount).where(
            BankAccount.user_id == user.id, BankAccount.external_id == a.external_id
        )
        existing = await session.scalar(stmt)
        if existing is None:
            existing = BankAccount(
                user_id=user.id,
                name=a.name,
                institution=a.institution,
                account_type=a.account_type,
                currency=a.currency,
                balance_minor=a.balance_minor,
                masked_number=a.masked_number,
                provider=a.provider,
                external_id=a.external_id,
                last_synced_at=datetime.now(UTC),
            )
            session.add(existing)
            await session.flush()
        else:
            existing.balance_minor = a.balance_minor
            existing.last_synced_at = datetime.now(UTC)
        account_map[a.external_id] = existing

    # Insert transactions that do not yet exist (single batch query for existing external_ids)
    existing_ext_ids = await _get_existing_external_ids(session, user.id)
    new_txns: list[Transaction] = []
    for t in raw_txns:
        acct = account_map.get(t.account_external_id)
        if not acct or t.external_id in existing_ext_ids:
            continue
        existing_ext_ids.add(t.external_id)
        txn = _build_transaction(user, acct.id, t, user_rules)
        session.add(txn)
        new_txns.append(txn)

    if new_txns:
        await session.flush()
        all_txns = list(
            (
                await session.scalars(
                    select(Transaction)
                    .where(Transaction.user_id == user.id)
                    .order_by(Transaction.occurred_at)
                )
            ).all()
        )
        _annotate_recurring_and_anomalies(all_txns, user.base_currency)

    await session.commit()
    return await list_accounts(session, user.id)


def _annotate_recurring_and_anomalies(txns: list[Transaction], currency: str) -> None:
    """Populate `is_recurring`, `is_anomaly`, `anomaly_score`, and `anomaly_reason` on ORM rows."""
    if not txns:
        return
    report = build_ml_insights_report(
        [_to_txn_record(t) for t in txns], currency=currency, liquid_balance_minor=0
    )
    recurring_merchants = {b.merchant_name for b in report.recurring_bills}
    anomaly_map = {a.transaction_id: a for a in report.anomalies}
    for t in txns:
        t.is_recurring = t.direction == "debit" and t.merchant_name in recurring_merchants
        anom = anomaly_map.get(str(t.id))
        t.is_anomaly = anom is not None
        t.anomaly_score = anom.robust_zscore if anom is not None else 0.0
        t.anomaly_reason = anom.reason if anom is not None else None


async def list_accounts(session: AsyncSession, user_id: uuid.UUID) -> list[BankAccount]:
    stmt = (
        select(BankAccount).where(BankAccount.user_id == user_id).order_by(BankAccount.created_at)
    )
    return list((await session.scalars(stmt)).all())


async def list_transactions(
    session: AsyncSession,
    user_id: uuid.UUID,
    *,
    limit: int = 100,
    offset: int = 0,
    category: str | None = None,
    account_id: uuid.UUID | None = None,
) -> list[Transaction]:
    stmt = (
        select(Transaction)
        .where(Transaction.user_id == user_id)
        .order_by(desc(Transaction.occurred_at))
    )
    if category:
        stmt = stmt.where(Transaction.category == category)
    if account_id:
        stmt = stmt.where(Transaction.account_id == account_id)

    stmt = stmt.offset(offset).limit(limit)
    return list((await session.scalars(stmt)).all())


async def update_transaction_category(
    session: AsyncSession,
    user_id: uuid.UUID,
    transaction_id: uuid.UUID,
    new_category: str,
    *,
    apply_to_future: bool = True,
) -> Transaction:
    stmt = select(Transaction).where(
        Transaction.id == transaction_id, Transaction.user_id == user_id
    )
    txn = await session.scalar(stmt)
    if not txn:
        raise NotFoundError("Transaction not found")

    txn.category = new_category
    txn.category_source = "user"
    txn.category_confidence = 1.0

    if apply_to_future and txn.merchant_name:
        rule_stmt = select(CategoryRule).where(
            CategoryRule.user_id == user_id,
            CategoryRule.merchant_pattern == txn.merchant_name,
        )
        rule = await session.scalar(rule_stmt)
        if rule:
            rule.category = new_category
            rule.hit_count += 1
        else:
            rule = CategoryRule(
                user_id=user_id,
                merchant_pattern=txn.merchant_name,
                category=new_category,
                hit_count=1,
            )
            session.add(rule)

        sibling_txns = (
            await session.scalars(
                select(Transaction).where(
                    Transaction.user_id == user_id,
                    Transaction.merchant_name == txn.merchant_name,
                )
            )
        ).all()
        for sibling in sibling_txns:
            sibling.category = new_category
            sibling.category_source = "user"
            sibling.category_confidence = 1.0

    await session.commit()
    await session.refresh(txn)
    return txn


async def import_csv_statement(
    session: AsyncSession,
    user: User,
    account_id: uuid.UUID | None,
    csv_text: str,
) -> tuple[int, int]:
    if account_id is not None:
        acct = await session.get(BankAccount, account_id)
        if not acct or acct.user_id != user.id:
            raise NotFoundError("Account not found")
    else:
        acct = await session.scalar(
            select(BankAccount).where(
                BankAccount.user_id == user.id,
                BankAccount.external_id == "csv-import-default",
            )
        )
        if acct is None:
            acct = BankAccount(
                user_id=user.id,
                name="CSV Statement Account",
                institution="Statement Import",
                account_type="checking",
                currency=user.base_currency,
                balance_minor=0,
                masked_number="CSV-IMPORT",
                provider="csv",
                external_id="csv-import-default",
                last_synced_at=datetime.now(UTC),
            )
            session.add(acct)
            await session.flush()

    raw_items = parse_csv_statement(csv_text, default_currency=acct.currency)
    user_rules = await get_user_rules_map(session, user.id)
    existing_ext_ids = await _get_existing_external_ids(session, user.id)

    imported = 0
    skipped = 0
    for item in raw_items:
        if item.external_id in existing_ext_ids:
            skipped += 1
            continue
        existing_ext_ids.add(item.external_id)
        session.add(_build_transaction(user, acct.id, item, user_rules))
        imported += 1

    if imported > 0:
        await session.flush()
        all_txns = list(
            (
                await session.scalars(
                    select(Transaction)
                    .where(Transaction.user_id == user.id)
                    .order_by(Transaction.occurred_at)
                )
            ).all()
        )
        _annotate_recurring_and_anomalies(all_txns, user.base_currency)

    await session.commit()
    return imported, skipped


async def get_user_ml_insights(session: AsyncSession, user: User) -> MLInsightsResponse:
    # 1. Fetch user accounts to calculate liquid balance in base currency
    accts = await list_accounts(session, user.id)
    liquid_balance = sum(
        convert_minor_units(a.balance_minor, a.currency, user.base_currency)[0]
        for a in accts
        if a.account_type in ("savings", "checking", "wallet")
    )

    # 2. Fetch all historical transactions
    stmt = (
        select(Transaction).where(Transaction.user_id == user.id).order_by(Transaction.occurred_at)
    )
    db_txns = (await session.scalars(stmt)).all()
    user_rules = await get_user_rules_map(session, user.id)

    # 3. If user has no transactions yet, compute a read-only preview without mutating the DB
    if db_txns:
        txns_data = [_to_txn_record(t) for t in db_txns]
    else:
        raw_accounts, raw_txns = MockBankConnector().fetch_accounts_and_transactions()
        if not accts:
            liquid_balance += sum(
                convert_minor_units(ra.balance_minor, ra.currency, user.base_currency)[0]
                for ra in raw_accounts
                if ra.account_type in ("savings", "checking", "wallet")
            )
        txns_data = []
        for idx, rt in enumerate(raw_txns):
            base_minor, fx = convert_minor_units(rt.amount_minor, rt.currency, user.base_currency)
            cat_res = categorize_transaction(rt.raw_description, user_rules)
            txns_data.append(
                TxnRecord(
                    id=f"preview-{idx}",
                    occurred_at=rt.occurred_at,
                    direction=rt.direction,
                    amount_base_minor=base_minor,
                    merchant_name=cat_res.merchant_name,
                    category=cat_res.category,
                    category_source=cat_res.source,
                    category_confidence=cat_res.confidence,
                    amount_minor=rt.amount_minor,
                    currency=rt.currency,
                    fx_rate=fx,
                )
            )

    return build_ml_insights_report(
        txns_data,
        currency=user.base_currency,
        liquid_balance_minor=liquid_balance,
        user_rules_count=len(user_rules),
    )
