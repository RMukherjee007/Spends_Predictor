# NRI Yield Advisory API — Design Specification

## POST /optimize

Computes FCNR vs NRE routing recommendation using the 4-phase yield engine.

### Headers
| Header | Required | Description |
|--------|----------|-------------|
| `Idempotency-Key` | Yes | UUID for 24h deduplication |
| `X-Gateway-Timestamp` | Yes | Unix ms timestamp (±30s) |
| `X-Internal-Signature` | Yes | HMAC-SHA256 of `timestamp\|METHOD\|path\|SHA256(body)` |
| `X-User-Role` | Yes | `RM`, `SENIOR_RM`, or `TREASURY` |
| `X-Employee-ID` | Yes | Employee identifier |
| `traceparent` | No | W3C trace context |

### Request Body
```json
{
  "customer_id": "CUST123",
  "risk_profile": "MODERATE",
  "principal_amount": "50000.00",
  "base_currency": "USD",
  "value_date": "2026-06-24",
  "tenure_months": 36,
  "channel": "BRANCH",
  "branch_code": "GIFT-001",
  "india_inflation_rate": "4.50",
  "foreign_inflation_rate": "2.00",
  "assets": [{ "market_value": "10000.00", "currency": "USD", "asset_type": "SAVINGS_ACCOUNT" }],
  "liabilities": [{ "outstanding_principal": "2000000.00", "currency": "INR", "liability_type": "HOME_LOAN" }],
  "fx_rate_overrides": { "product_spot_rate": "84.5000", "forward_rates": { "36": "86.2000" } },
  "market_rates_override": { "fcnr_rate_pct": "5.50", "nre_rate_pct": "7.50", "override_reason": "VIP client" }
}
```

### Response (200)
```json
{
  "advisory": {
    "recommended_product": "FCNR",
    "compliance_warnings": ["FCNR(B) cannot be prematurely withdrawn within the first 12 months..."],
    "fx_risk_flag": false
  },
  "compliance": {
    "premature_withdrawal_note": "FCNR(B) deposits cannot be prematurely withdrawn...",
    "tax_treatment": "Interest on both NRE and FCNR(B) accounts is exempt...",
    "tds_applicable": false
  },
  "decision_trace": {
    "calculation_method": "REAL_PPP_ADJUSTED",
    "product_spot_rate_used": "83.5000",
    "product_forward_rate_used": "86.5000",
    "rates_source": "LIVE_MARKET_API",
    "alm_policy_version": "POL-2026-Q2",
    "total_assets_inr": "835000.00",
    "total_liabilities_inr": "2000000.00",
    "debt_to_asset_ratio": "2.40",
    "alm_penalty_applied": true,
    "alm_penalty_pct": "0.35",
    "alm_penalty_amount": "146125.00",
    "fcnr_effective_yield_pct": "4.85",
    "nre_effective_yield_pct": "5.99",
    "fcnr_real_yield_pct": "2.79",
    "nre_real_yield_pct": "1.43"
  },
  "metadata": {
    "recommendation_id": "uuid-v4",
    "customer_id": "CUST123",
    "computed_at": "2026-06-24T...",
    "execution_time_ms": 42,
    "portfolio_enrichment": { "source": "CACHE_HIT", "positions_injected": { "asset_count": 1, "liability_count": 1 } }
  }
}
```

## GET /predict

Returns currency forward curve projection for charting.

### Query Parameters
- `base_currency` (required) — e.g. `USD`
- `target_currency` (required) — e.g. `INR`
- `tenure_months` (optional, default 36)

### Response
```json
{
  "pair": "USD/INR",
  "spot_rate": 83.5,
  "trend": "DEPRECIATING",
  "total_change_pct": 5.1497,
  "implied_annualized_differential_pct": 7.25,
  "graph": {
    "key_points": [
      { "tenure_months": 0, "rate": 83.5, "forward_premium_pct": 0, "direction": "FLAT" },
      { "tenure_months": 12, "rate": 85.1, "forward_premium_pct": 1.9162, "direction": "INR_DEPRECIATION" }
    ],
    "interpolated_monthly": [
      { "month": 0, "rate": 83.5 },
      { "month": 1, "rate": 83.6333 }
    ]
  }
}
```

## GET /logs

Paginated audit log history.

### Query Parameters
- `page` (default 1)
- `limit` (default 50, max 200)
- `customer_id` (optional filter)
- `product` (optional filter: FCNR/NRE/EQUAL_YIELD)
- `from_date` / `to_date` (optional ISO date range)

## GET /logs/download

Download all logs as CSV or JSON.

### Query Parameters
- `format` — `json` (default) or `csv`
- `customer_id`, `product` (optional filters)

## GET /reports/:recommendation_id

Download a PDF report for a stored recommendation.

## Error Format (RFC 7807)

All errors return `application/problem+json`:
```json
{
  "type": "https://api.bank.com/errors/invalid-currency",
  "title": "Invalid Currency",
  "status": 422,
  "detail": "base_currency \"XYZ\" is not supported.",
  "instance": "00-4bf92f35...-01",
  "error_code": "INVALID_CURRENCY",
  "invalid_fields": { "base_currency": "must be one of: USD, GBP, EUR, ..." }
}
```

### Error Codes
| Code | Status | Description |
|------|--------|-------------|
| `MISSING_REQUIRED_FIELD` | 400 | Required field omitted |
| `INVALID_FORMAT` | 400 | Field fails regex/type validation |
| `SIGNATURE_MISMATCH` | 401 | HMAC validation failed |
| `TIMESTAMP_SKEW` | 401 | Timestamp > ±30s from server |
| `INSUFFICIENT_ROLE` | 403 | RM cannot use overrides |
| `IDEMPOTENCY_CONFLICT` | 409 | Key reused with different body |
| `INVALID_CURRENCY` | 422 | Unsupported currency |
| `TENURE_OUT_OF_RANGE` | 422 | Tenure not 12-60 months |
| `PRINCIPAL_BELOW_MINIMUM` | 422 | Below USD 1000 equivalent |
| `GCC_REQUIRES_IFSC_BRANCH` | 422 | GCC needs GIFT City branch |
| `RATE_OVERRIDE_LIMIT_EXCEEDED` | 422 | Override exceeds deviation cap |
| `FUTURE_APPROVAL_TIMESTAMP` | 422 | Approval timestamp in future |
| `DEPENDENCY_TIMEOUT` | 503 | Downstream service unreachable |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |
