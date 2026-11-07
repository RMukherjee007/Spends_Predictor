"""Persistent JSON-RPC worker and CLI bridge invoked by the Java Spring Boot backend."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict
from datetime import UTC, datetime
from typing import Any

from app.native.bridge import ensure_quantcore_compiled, is_cpp_kernel_active
from app.services.categorizer import categorize_transaction, get_global_classifier
from app.services.connectors import MockBankConnector, RawTransactionPayload, parse_csv_statement
from app.services.fx import convert_minor_units
from app.services.ml_engine import TxnRecord, annotate_transactions, build_ml_insights_report


def _parse_dt(val: str) -> datetime:
    s = val.strip()
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    dt = datetime.fromisoformat(s)
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


def _dict_to_txn_record(d: dict[str, Any]) -> TxnRecord:
    return TxnRecord(
        id=str(d.get("id") or d.get("external_id") or "tx"),
        occurred_at=_parse_dt(str(d["occurred_at"])),
        direction=str(d["direction"]),
        amount_base_minor=int(d["amount_base_minor"]),
        merchant_name=str(d["merchant_name"]),
        category=str(d["category"]),
        category_source=str(d.get("category_source", "rule")),
        category_confidence=float(d.get("category_confidence", 0.9)),
        amount_minor=int(d.get("amount_minor", d["amount_base_minor"])),
        currency=str(d.get("currency", "INR")),
        fx_rate=float(d.get("fx_rate", 1.0)),
    )


def _enrich_raw_txn(
    t: RawTransactionPayload,
    base_currency: str,
    user_rules: dict[str, str],
    fallback_id: str,
) -> tuple[dict[str, Any], TxnRecord]:
    base_minor, fx = convert_minor_units(t.amount_minor, t.currency, base_currency)
    cat = categorize_transaction(t.raw_description, user_rules)
    dt_utc = (
        t.occurred_at.astimezone(UTC) if t.occurred_at.tzinfo else t.occurred_at.replace(tzinfo=UTC)
    )
    dt_iso = dt_utc.isoformat()
    row: dict[str, Any] = {
        "external_id": t.external_id,
        "account_external_id": t.account_external_id,
        "occurred_at": dt_iso,
        "direction": t.direction,
        "amount_minor": t.amount_minor,
        "currency": t.currency,
        "amount_base_minor": base_minor,
        "fx_rate": fx,
        "raw_description": t.raw_description,
        "merchant_name": cat.merchant_name,
        "category": cat.category,
        "category_source": cat.source,
        "category_confidence": cat.confidence,
        "is_recurring": False,
        "is_anomaly": False,
        "anomaly_score": 0.0,
        "anomaly_reason": None,
    }
    rec = TxnRecord(
        id=t.external_id or fallback_id,
        occurred_at=dt_utc,
        direction=t.direction,
        amount_base_minor=base_minor,
        merchant_name=cat.merchant_name,
        category=cat.category,
        category_source=cat.source,
        category_confidence=cat.confidence,
        amount_minor=t.amount_minor,
        currency=t.currency,
        fx_rate=fx,
    )
    return row, rec


def _handle_sync_mock(payload: dict[str, Any]) -> dict[str, Any]:
    base_currency = str(payload.get("base_currency", "INR"))
    user_rules: dict[str, str] = dict(payload.get("user_rules", {}))
    raw_accounts, raw_txns = MockBankConnector().fetch_accounts_and_transactions()

    enriched = [
        _enrich_raw_txn(t, base_currency, user_rules, f"mock-{i}") for i, t in enumerate(raw_txns)
    ]
    txns_out = [pair[0] for pair in enriched]
    records = [pair[1] for pair in enriched]

    if records:
        recurring_bills, anomalies = annotate_transactions(records)
        rec_merchants = {b.merchant_name for b in recurring_bills}
        anom_map = {a.transaction_id: a for a in anomalies}
        for row in txns_out:
            row["is_recurring"] = (
                row["direction"] == "debit" and row["merchant_name"] in rec_merchants
            )
            anom = anom_map.get(str(row["external_id"]))
            if anom is not None:
                row["is_anomaly"] = True
                row["anomaly_score"] = anom.robust_zscore
                row["anomaly_reason"] = anom.reason

    return {"accounts": [asdict(a) for a in raw_accounts], "transactions": txns_out}


def _handle_parse_csv(payload: dict[str, Any]) -> dict[str, Any]:
    csv_text = str(payload.get("csv_content", ""))
    account_currency = str(payload.get("account_currency", "INR"))
    base_currency = str(payload.get("base_currency", "INR"))
    user_rules: dict[str, str] = dict(payload.get("user_rules", {}))

    raw_items = parse_csv_statement(csv_text, default_currency=account_currency)
    txns_out = [
        _enrich_raw_txn(item, base_currency, user_rules, f"csv-{i}")[0]
        for i, item in enumerate(raw_items)
    ]
    return {"transactions": txns_out}


def _handle_insights(payload: dict[str, Any]) -> dict[str, Any]:
    base_currency = str(payload.get("base_currency", "INR"))
    liquid_balance_minor = int(payload.get("liquid_balance_minor", 0))
    user_rules: dict[str, str] = dict(payload.get("user_rules", {}))
    raw_txns: list[dict[str, Any]] = list(payload.get("transactions", []))

    records = [_dict_to_txn_record(d) for d in raw_txns]
    report = build_ml_insights_report(
        records,
        currency=base_currency,
        liquid_balance_minor=liquid_balance_minor,
        user_rules_count=len(user_rules),
    )
    return report.model_dump(mode="json")


def _handle_annotate(payload: dict[str, Any]) -> dict[str, Any]:
    raw_txns: list[dict[str, Any]] = list(payload.get("transactions", []))
    if not raw_txns:
        return {"recurring_merchants": [], "anomalies": []}
    records = [_dict_to_txn_record(d) for d in raw_txns]
    recurring_bills, anomalies = annotate_transactions(records)
    return {
        "recurring_merchants": [b.merchant_name for b in recurring_bills],
        "anomalies": [a.model_dump(mode="json") for a in anomalies],
    }


_HANDLERS = {
    "sync-mock": _handle_sync_mock,
    "parse-csv": _handle_parse_csv,
    "annotate": _handle_annotate,
    "insights": _handle_insights,
}


def _run_persistent_worker() -> None:
    """Keep scikit-learn classifier and C++20 native kernel pre-warmed in a long-lived worker."""
    ensure_quantcore_compiled()
    is_cpp_kernel_active()
    get_global_classifier()

    for line in sys.stdin:
        raw = line.strip()
        if not raw:
            continue
        try:
            req = json.loads(raw)
            cmd = str(req.get("cmd", ""))
            payload = dict(req.get("payload", {}))
            if cmd == "health":
                out: dict[str, Any] = {"cpp_kernel": is_cpp_kernel_active()}
            elif cmd in _HANDLERS:
                out = _HANDLERS[cmd](payload)
            else:
                raise ValueError(f"Unknown worker command: {cmd}")
            sys.stdout.write(json.dumps({"ok": True, "data": out}) + "\n")
        except Exception as exc:
            sys.stdout.write(json.dumps({"ok": False, "error": str(exc)}) + "\n")
        sys.stdout.flush()


def main() -> None:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "health"
    if cmd == "worker":
        _run_persistent_worker()
        return
    if cmd == "compile-native":
        print(
            json.dumps(
                {"compiled": ensure_quantcore_compiled(), "cpp_kernel": is_cpp_kernel_active()}
            )
        )
        return
    if cmd == "health":
        print(json.dumps({"cpp_kernel": is_cpp_kernel_active()}))
        return

    payload = json.loads(sys.stdin.read() or "{}")
    if cmd not in _HANDLERS:
        raise ValueError(f"Unknown command: {cmd}")
    print(json.dumps(_HANDLERS[cmd](payload)))


if __name__ == "__main__":
    main()
