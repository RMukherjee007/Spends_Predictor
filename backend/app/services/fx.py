"""Multi-currency exchange rate conversion for minor-unit integer amounts."""

from __future__ import annotations

from app.core.currencies import minor_units

# Approximate reference rates relative to 1 USD (used offline/deterministically;
# Phase 3 worker can refresh these daily from an FX feed).
USD_RATES: dict[str, float] = {
    "USD": 1.0,
    "INR": 84.0,
    "EUR": 0.92,
    "GBP": 0.79,
    "AED": 3.67,
    "SGD": 1.34,
    "JPY": 152.0,
    "CAD": 1.38,
    "AUD": 1.52,
    "CHF": 0.88,
    "KWD": 0.307,
    "CNY": 7.24,
    "HKD": 7.78,
    "NZD": 1.66,
    "SEK": 10.6,
    "NOK": 10.9,
    "DKK": 6.86,
    "PLN": 4.01,
    "CZK": 23.3,
    "HUF": 368.0,
    "ILS": 3.74,
    "ZAR": 17.7,
    "BRL": 5.70,
    "MXN": 19.9,
    "KRW": 1380.0,
    "TWD": 32.1,
    "THB": 33.8,
    "MYR": 4.38,
    "IDR": 15700.0,
    "PHP": 58.1,
    "VND": 25300.0,
    "SAR": 3.75,
    "QAR": 3.64,
    "BHD": 0.376,
    "OMR": 0.385,
    "JOD": 0.709,
    "TRY": 34.2,
    "RUB": 96.5,
    "BDT": 119.5,
    "PKR": 278.0,
    "LKR": 293.0,
    "NPR": 134.4,
    "KES": 129.0,
    "NGN": 1650.0,
    "EGP": 48.7,
    "CLP": 950.0,
    "COP": 4300.0,
    "PEN": 3.76,
    "ARS": 985.0,
}


def get_fx_rate(from_currency: str, to_currency: str) -> float:
    """Return 1 major unit of `from_currency` expressed in major units of `to_currency`."""
    src = from_currency.upper()
    dst = to_currency.upper()
    if src == dst:
        return 1.0
    src_per_usd = USD_RATES.get(src, 1.0)
    dst_per_usd = USD_RATES.get(dst, 1.0)
    return dst_per_usd / src_per_usd


def convert_minor_units(
    amount_minor: int, from_currency: str, to_currency: str
) -> tuple[int, float]:
    """Convert `amount_minor` from `from_currency` to `to_currency` minor units.

    Returns `(converted_minor, fx_rate)`.
    """
    src = from_currency.upper()
    dst = to_currency.upper()
    if src == dst:
        return amount_minor, 1.0

    rate = round(get_fx_rate(src, dst), 10)
    src_digits = minor_units(src)
    dst_digits = minor_units(dst)
    major_amount = amount_minor / (10**src_digits)
    converted_major = major_amount * rate
    converted_minor = round(converted_major * (10**dst_digits))
    return int(converted_minor), rate
