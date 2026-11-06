"""Provider-agnostic BankConnector interface, MockBankConnector (sandbox generator), and CSV importer.

Designed so Setu/Finvu Account Aggregator (India), Plaid (US), and TrueLayer (UK/EU) can plug into
`BankConnector` without modifying downstream transaction, categorization, or ML pipelines.
"""

from __future__ import annotations

import csv
import hashlib
import io
import random
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Protocol


@dataclass(frozen=True, slots=True)
class RawAccountPayload:
    external_id: str
    name: str
    institution: str
    account_type: str
    currency: str
    balance_minor: int
    masked_number: str
    provider: str


@dataclass(frozen=True, slots=True)
class RawTransactionPayload:
    external_id: str
    account_external_id: str
    occurred_at: datetime
    direction: str  # "debit" | "credit"
    amount_minor: int
    currency: str
    raw_description: str


class BankConnector(Protocol):
    provider_name: str

    def fetch_accounts_and_transactions(
        self, *, reference_now: datetime | None = None
    ) -> tuple[list[RawAccountPayload], list[RawTransactionPayload]]: ...


class MockBankConnector:
    """Realistic 6-month multi-account, multi-currency sandbox dataset generator.

    Embeds genuine statistical structure for the ML engine to discover:
      1. Monthly salary credits on the 1st of every month.
      2. Fixed & semi-variable recurring bills (House Rent, Airtel Fiber, BESCOM Electricity,
         Netflix, Spotify, Zerodha SIP, and Cult.fit Gym with a +20% silent price hike in the
         latest 2 months).
      3. Behavioral patterns:
         - Weekend dining & entertainment spikes (Fri-Sun)
         - Payday splurge window (days 1-5 of each month)
         - Gradual CUSUM lifestyle creep (+8% per month growth in dining/food delivery over the
           most recent 3 months)
         - Late-night food/shopping impulse orders (23:00 - 01:00)
      4. Multi-currency card usage (USD SaaS subscriptions & international spend on Wise USD card).
      5. Clear anomalies (a 2:15 AM duplicate luxury electronics charge and an out-of-distribution
         spike) for IsolationForest + Robust MAD Z-Score to flag.
    """

    provider_name = "mock_aa"

    def fetch_accounts_and_transactions(
        self, *, reference_now: datetime | None = None
    ) -> tuple[list[RawAccountPayload], list[RawTransactionPayload]]:
        now = reference_now or datetime.now(UTC)
        rng = random.Random(20261027)  # Deterministic seed for reproducible ML insights  # noqa: S311

        accounts = [
            RawAccountPayload(
                external_id="mock-hdfc-savings",
                name="HDFC Primary Savings (UPI)",
                institution="HDFC Bank",
                account_type="savings",
                currency="INR",
                balance_minor=14250000,  # ₹1,42,500.00
                masked_number="XXXX-XXXX-4821",
                provider=self.provider_name,
            ),
            RawAccountPayload(
                external_id="mock-icici-sapphiro",
                name="ICICI Sapphiro Credit Card",
                institution="ICICI Bank",
                account_type="credit_card",
                currency="INR",
                balance_minor=3480000,  # ₹34,800.00 current statement balance
                masked_number="4111-XXXX-XXXX-9012",
                provider=self.provider_name,
            ),
            RawAccountPayload(
                external_id="mock-wise-usd",
                name="Wise Multi-Currency Wallet (USD)",
                institution="Wise",
                account_type="wallet",
                currency="USD",
                balance_minor=64500,  # $645.00
                masked_number="USD-ACCT-7731",
                provider=self.provider_name,
            ),
        ]

        txns: list[RawTransactionPayload] = []
        start_date = (now - timedelta(days=175)).replace(hour=10, minute=0, second=0, microsecond=0)

        def _add(
            ext_id: str,
            acct: str,
            dt: datetime,
            amount: int,
            desc: str,
            *,
            direction: str = "debit",
            currency: str = "INR",
        ) -> None:
            txns.append(
                RawTransactionPayload(
                    external_id=ext_id,
                    account_external_id=acct,
                    occurred_at=dt,
                    direction=direction,
                    amount_minor=amount,
                    currency=currency,
                    raw_description=desc,
                )
            )

        groc_merchants = [
            "UPI/112233/SWIGGY INSTAMART GROCERY/BLR",
            "UPI/445566/BLINKIT GROCERS PVT LTD/BLR",
            "UPI/778899/ZEPTO SUPERMARKET/BLR",
            "POS DMART AVENUE SUPERMARTS BANGALORE",
        ]
        commute_merchants = [
            "UPI/443322/UBER INDIA SYSTEMS TRIP/BLR",
            "UPI/332211/NAMMA YATRI AUTO/BLR",
            "UPI/776655/OLA CABS RIDE/BLR",
        ]
        dining_merchants = [
            "UPI/301928/SWIGGY FOOD ORDER/BLR",
            "UPI/982341/ZOMATO LIMITED/BLR",
            "UPI/192831/THIRD WAVE COFFEE ROASTERS/BLR",
            "UPI/228192/TRUFFLES KORAMANGALA/BLR",
        ]
        shop_merchants = [
            "POS AMZN MKTP IN SHOPPING",
            "POS MYNTRA DESIGNS APPAREL",
            "UPI/FLIPKART INTERNET PVT LTD",
        ]

        # Iterate day by day across ~6 months up to `now`
        total_days = (now.date() - start_date.date()).days + 1
        for day_offset in range(total_days):
            day_dt = start_date + timedelta(days=day_offset)
            dom = day_dt.day
            dow = day_dt.weekday()  # 0=Mon .. 6=Sun
            ym = day_dt.strftime("%Y%m")
            ymd = day_dt.strftime("%Y%m%d")
            # Month index 0..5 from start; last 3 months exhibit gradual dining lifestyle creep
            month_idx = day_offset // 30
            creep_multiplier = 1.0 + max(0, month_idx - 2) * 0.11

            # --- 1. Monthly Salary Credit (1st of month) & 2. Fixed/Semi-Variable Recurring Bills ---
            if dom == 1:
                _add(f"mock-sal-{ym}", "mock-hdfc-savings", day_dt.replace(hour=9, minute=15), 14500000, "NEFT/TECHCORP INDIA SALARY CREDIT/BLR", direction="credit")
            elif dom == 3:
                _add(f"mock-rent-{ym}", "mock-hdfc-savings", day_dt.replace(hour=10, minute=30), 2400000, "UPI/998877/NOBROKER RENT PAYMENT/BANGALORE")
            elif dom == 5:
                _add(f"mock-sip-{ym}", "mock-hdfc-savings", day_dt.replace(hour=11, minute=0), 1000000, "ACH/ZERODHA BROKING SIP/MUMBAI")
            elif dom == 7:
                _add(f"mock-airtel-{ym}", "mock-icici-sapphiro", day_dt.replace(hour=14, minute=20), 117900, "UPI/223344/AIRTEL FIBER BROADBAND/IN")
            elif dom == 10:
                # BESCOM electricity varies slightly around ₹1,850
                elec_minor = max(140000, int(rng.gauss(185000, 14000)))
                _add(f"mock-bescom-{ym}", "mock-hdfc-savings", day_dt.replace(hour=16, minute=5), elec_minor, "UPI/665544/BESCOM ELECTRICITY BILL/BANGALORE")
            elif dom == 12:
                _add(f"mock-netflix-{ym}", "mock-icici-sapphiro", day_dt.replace(hour=8, minute=0), 64900, "ACH/NETFLIX.COM SUBSCRIPTION/MUMBAI")
            elif dom == 15:
                # Cult.fit Gym with a silent +22% price hike in the most recent 2 months!
                gym_amount = 195000 if month_idx >= 4 else 159900
                _add(f"mock-cultfit-{ym}", "mock-icici-sapphiro", day_dt.replace(hour=7, minute=30), gym_amount, "UPI/CULTFIT HEALTHCARE MEMBERSHIP/BANGALORE")
            elif dom == 18:
                # USD subscription on Wise USD card ($20.00 = 2000 cents)
                _add(f"mock-openai-{ym}", "mock-wise-usd", day_dt.replace(hour=18, minute=45), 2000, "POS OPENAI CHATGPT PLUS SUB SAN FRANCISCO", currency="USD")

            # --- 3. Weekly Groceries (2-3 times a week) ---
            if dow in (1, 4, 6) and rng.random() < 0.78:
                groc_amt = max(28000, int(rng.gauss(82000, 22000)))
                _add(f"mock-groc-{ymd}", "mock-hdfc-savings", day_dt.replace(hour=rng.choice([11, 18, 20]), minute=12), groc_amt, rng.choice(groc_merchants))

            # --- 4. Commute / Transport (Weekdays) ---
            if dow < 5 and rng.random() < 0.65:
                ride_amt = max(11000, int(rng.gauss(29000, 8000)))
                _add(f"mock-ride-{ymd}", "mock-hdfc-savings", day_dt.replace(hour=9, minute=rng.randint(5, 50)), ride_amt, rng.choice(commute_merchants))

            # --- 5. Dining & Weekend Spikes + Lifestyle Creep ---
            is_weekend = dow >= 4
            if rng.random() < (0.72 if is_weekend else 0.35):
                base_meal = 78000 if is_weekend else 42000
                meal_amt = max(18000, int(rng.gauss(base_meal, 16000) * creep_multiplier))
                hour = rng.choice([20, 21, 23]) if is_weekend else rng.choice([13, 20])
                _add(
                    f"mock-dine-{ymd}",
                    "mock-icici-sapphiro" if is_weekend else "mock-hdfc-savings",
                    day_dt.replace(hour=hour, minute=rng.randint(10, 55)),
                    meal_amt,
                    rng.choice(dining_merchants),
                )

            # --- 6. Payday Splurge Shopping (Days 1..5 of month) ---
            if 1 <= dom <= 5 and rng.random() < 0.42:
                shop_amt = max(89900, int(rng.gauss(245000, 65000)))
                _add(f"mock-shop-{ymd}", "mock-icici-sapphiro", day_dt.replace(hour=22, minute=35), shop_amt, rng.choice(shop_merchants))

        # --- 7. Inject Explicit Anomalies in the Current Month ---
        recent_dt = (now - timedelta(days=3)).replace(hour=2, minute=15, second=0, microsecond=0)
        _add("mock-anomaly-late-electronics", "mock-icici-sapphiro", recent_dt, 1890000, "POS CROMA ELECTRONICS STORE LATE NIGHT")
        _add(
            "mock-anomaly-fine-dining-spike",
            "mock-icici-sapphiro",
            (now - timedelta(days=6)).replace(hour=23, minute=40, second=0, microsecond=0),
            685000,
            "POS STARBUCKS COFFEE MUMBAI GROUP ORDER",
        )

        return accounts, txns


def parse_csv_statement(
    csv_text: str,
    *,
    default_currency: str = "INR",
    account_external_id: str = "csv-import",
) -> list[RawTransactionPayload]:
    """Parse a bank/card statement CSV with flexible column header detection."""
    import math

    from app.core.currencies import is_valid_currency, minor_units

    reader = csv.DictReader(io.StringIO(csv_text.strip()))
    if not reader.fieldnames:
        return []

    headers = {h.strip().lower(): h for h in reader.fieldnames if h}

    def _pick(row: dict[str, str], candidates: tuple[str, ...]) -> str:
        for cand in candidates:
            if cand in headers and row.get(headers[cand]):
                return row[headers[cand]].strip()
        return ""

    results: list[RawTransactionPayload] = []
    for idx, row in enumerate(reader):
        if idx >= 1000:
            break
        raw_date = _pick(
            row, ("date", "transaction_date", "occurred_at", "value_date", "posted_date")
        )
        raw_desc = _pick(
            row, ("description", "narration", "merchant", "particulars", "remarks", "details")
        )
        raw_amount = _pick(row, ("amount", "value", "transaction_amount"))
        raw_debit = _pick(row, ("debit", "withdrawal", "dr"))
        raw_credit = _pick(row, ("credit", "deposit", "cr"))
        raw_dir = _pick(row, ("direction", "type", "dr_cr", "indicator")).lower()
        raw_curr = (_pick(row, ("currency", "curr", "iso_currency")) or default_currency).upper().strip()

        if not raw_curr or len(raw_curr) != 3 or not is_valid_currency(raw_curr):
            continue

        inferred_dir: str | None = None
        if not raw_amount:
            if raw_credit:
                raw_amount = raw_credit
                inferred_dir = "credit"
            elif raw_debit:
                raw_amount = raw_debit
                inferred_dir = "debit"

        if not raw_desc or not raw_amount or not raw_date:
            continue

        cleaned_amt = (
            raw_amount.replace(",", "")
            .replace("₹", "")
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
            .replace("¥", "")
            .strip()
        )
        try:
            major_val = float(cleaned_amt)
        except ValueError:
            continue

        if not math.isfinite(major_val):
            continue

        direction = inferred_dir or "debit"
        if major_val < 0:
            direction = "debit"
            major_val = abs(major_val)
        elif inferred_dir is None and raw_dir in ("credit", "cr", "inflow", "deposit"):
            direction = "credit"

        if major_val <= 0 or major_val > 1_000_000_000:
            continue

        occurred: datetime | None = None
        for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%Y/%m/%d", "%m/%d/%Y"):
            try:
                occurred = datetime.strptime(raw_date[:10], fmt).replace(
                    hour=12, minute=0, tzinfo=UTC
                )
                break
            except ValueError:
                continue

        if occurred is None:
            continue

        digits = minor_units(raw_curr)
        amount_minor = round(major_val * (10**digits))
        if amount_minor <= 0:
            continue

        digest = hashlib.sha256(
            f"{occurred.date().isoformat()}:{raw_desc}:{amount_minor}:{idx}".encode()
        ).hexdigest()[:16]

        results.append(
            RawTransactionPayload(
                external_id=f"csv-{digest}",
                account_external_id=account_external_id,
                occurred_at=occurred,
                direction=direction,
                amount_minor=amount_minor,
                currency=raw_curr,
                raw_description=raw_desc,
            )
        )
    return results
