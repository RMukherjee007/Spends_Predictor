from __future__ import annotations

from app.ml_cli import (
    _dict_to_txn_record,
    _handle_annotate,
    _handle_insights,
    _handle_parse_csv,
    _handle_sync_mock,
)
from app.native.bridge import (
    cpp_mint_reconcile_forecasts,
    cpp_page_cusum_stat,
    is_cpp_kernel_active,
    run_cpp_monte_carlo_runway,
    wald_first_passage_probability,
)
from app.services.categorizer import categorize_transaction, clean_merchant_name
from app.services.connectors import parse_csv_statement
from app.services.fx import convert_minor_units
from app.services.ml_engine import verify_financial_ledger


def test_merchant_disambiguation_and_categorizer() -> None:
    # Ensure multi-word brand prefixes do not hijack shorter or sibling brand names
    assert clean_merchant_name("UPI/301928/SWIGGY FOOD ORDER/BLR") == "Swiggy"
    assert clean_merchant_name("UPI/112233/SWIGGY INSTAMART GROCERY") == "Swiggy Instamart"
    assert clean_merchant_name("UPI/TATA 1MG LABS DIAGNOSTICS") == "Tata 1mg"
    assert clean_merchant_name("BILLPAY/TATA POWER ELECTRICITY") == "Tata Power"
    assert clean_merchant_name("POS APPLE.COM/BILL ICLOUD") == "Apple Services"
    assert clean_merchant_name("POS APPLE STORE MUMBAI") == "Apple Store"

    res_rule = categorize_transaction("UPI/301928/SWIGGY FOOD ORDER/BLR")
    assert res_rule.merchant_name == "Swiggy"
    assert res_rule.category == "dining"
    assert res_rule.source == "rule"

    res_user = categorize_transaction(
        "UPI/301928/SWIGGY FOOD ORDER/BLR", user_rules={"Swiggy": "entertainment"}
    )
    assert res_user.category == "entertainment"
    assert res_user.source == "user"
    assert res_user.confidence >= 0.99


def test_fx_and_csv_parser_edge_cases() -> None:
    same_minor, same_rate = convert_minor_units(12500, "INR", "INR")
    assert same_minor == 12500
    assert same_rate == 1.0

    jpy_to_inr_minor, _ = convert_minor_units(1520, "JPY", "INR")
    assert jpy_to_inr_minor == 84000  # 1520 JPY == 10 USD == 840.00 INR (84000 paise)

    csv_text = (
        "Date,Narration,Debit,Credit,Currency\n"
        "2026-10-01,TOKYO RAMEN,1500,,JPY\n"
        "2026-10-02,KUWAIT OIL CO,,12.500,KWD\n"
        "2026-10-03,BAD NAN ROW,NaN,,USD\n"
        "2026-10-04,BAD INF ROW,inf,,USD\n"
        "2026-10-05,BAD CURRENCY,50.00,,FOO\n"
        "2026-10-06,ZERO AMOUNT,0.00,,INR\n"
    )
    rows = parse_csv_statement(csv_text, default_currency="INR")
    assert len(rows) == 2
    assert rows[0].currency == "JPY"
    assert rows[0].amount_minor == 1500
    assert rows[0].direction == "debit"
    assert rows[1].currency == "KWD"
    assert rows[1].amount_minor == 12500
    assert rows[1].direction == "credit"


def test_ml_cli_sync_and_insights() -> None:
    synced = _handle_sync_mock({"base_currency": "INR", "user_rules": {}})
    assert len(synced["accounts"]) >= 1
    assert len(synced["transactions"]) >= 10

    insights = _handle_insights(
        {
            "base_currency": "INR",
            "liquid_balance_minor": 5_000_000,
            "user_rules": {},
            "transactions": synced["transactions"],
        }
    )
    assert insights["currency"] == "INR"
    assert (
        insights["forecast_p10_minor"]
        <= insights["forecast_p50_minor"]
        <= insights["forecast_p90_minor"]
    )
    assert len(insights["runway"]["trajectory"]) == 16
    assert insights["java_audit"]["is_valid"] is True
    assert insights["java_audit"]["total_debits_minor"] > 0


def test_ml_cli_parse_csv_and_annotate() -> None:
    csv_text = (
        "date,description,amount,direction,currency\n"
        "2026-04-01,Monthly Payroll,4200.00,credit,USD\n"
        "2026-04-02,City Apartments Rent,1650.00,debit,USD\n"
        "2026-04-03,Whole Foods Market,142.50,debit,USD\n"
        "2026-04-05,Netflix.com,15.99,debit,USD\n"
        "2026-04-06,Uber Trip,24.80,debit,USD\n"
    )
    parsed = _handle_parse_csv(
        {
            "csv_content": csv_text,
            "account_currency": "USD",
            "base_currency": "USD",
            "user_rules": {},
        }
    )
    txs = parsed["transactions"]
    assert len(txs) == 5

    for idx, row in enumerate(txs, start=1):
        row["id"] = f"tx-{idx}"

    annotated = _handle_annotate({"base_currency": "USD", "transactions": txs})
    assert "recurring_merchants" in annotated
    assert "anomalies" in annotated


def test_native_cpp_and_ledger_verifier() -> None:
    assert isinstance(is_cpp_kernel_active(), bool)
    mc = run_cpp_monte_carlo_runway(
        initial_balance=500_000,
        safe_threshold=100_000,
        daily_disc_pool=[2_000.0, 3_500.0, 1_800.0, 4_200.0],
        net_scheduled_by_day=[0.0] * 30,
        days_to_payday=15,
    )
    assert len(mc.p50_by_day) == 31
    assert 0.0 <= mc.wald_exact_prob <= 1.0

    wald_prob = wald_first_passage_probability(500_000.0, 100_000.0, -2_500.0, 1_200.0, 15.0)
    assert 0.0 <= wald_prob <= 1.0

    cusum = cpp_page_cusum_stat([1000, 1100, 1500, 1800, 2200], 1000.0, 200.0)
    assert cusum > 0.0

    reconciled_total, reconciled_cats = cpp_mint_reconcile_forecasts(
        [10_000.0, 20_000.0, 15_000.0], [100.0, 400.0, 200.0], 48_000.0, 500.0
    )
    assert reconciled_total == sum(reconciled_cats)

    synced = _handle_sync_mock({"base_currency": "INR", "user_rules": {}})
    records = [
        _dict_to_txn_record({**row, "id": f"tx-{idx}"})
        for idx, row in enumerate(synced["transactions"][:10], start=1)
    ]
    audit = verify_financial_ledger(records)
    assert audit.is_valid is True
    assert len(audit.audit_seal_sha256) == 64
