from __future__ import annotations

from httpx import AsyncClient


async def _get_auth_token(client: AsyncClient) -> str:
    resp = await client.post(
        "/api/v1/auth/dev-login", json={"email": "finance_test@example.com", "client": "mobile"}
    )
    assert resp.status_code == 200
    return str(resp.json()["tokens"]["access_token"])


async def test_sync_mock_accounts_and_list(client: AsyncClient) -> None:
    token = await _get_auth_token(client)
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Sync mock bank data
    sync_resp = await client.post("/api/v1/accounts/sync-mock", headers=headers)
    assert sync_resp.status_code == 200
    accounts = sync_resp.json()
    assert len(accounts) >= 3
    currencies = {a["currency"] for a in accounts}
    assert "INR" in currencies and "USD" in currencies

    # 2. List accounts
    list_resp = await client.get("/api/v1/accounts", headers=headers)
    assert list_resp.status_code == 200
    assert len(list_resp.json()) == len(accounts)


async def test_transactions_and_active_learning_category_update(client: AsyncClient) -> None:
    token = await _get_auth_token(client)
    headers = {"Authorization": f"Bearer {token}"}

    # Ensure accounts are synced
    await client.post("/api/v1/accounts/sync-mock", headers=headers)

    # List transactions
    txns_resp = await client.get("/api/v1/transactions?limit=20", headers=headers)
    assert txns_resp.status_code == 200
    txns = txns_resp.json()
    assert len(txns) > 0

    first_txn = txns[0]
    first_id = first_txn["id"]

    # Re-categorize to test active learning
    patch_resp = await client.patch(
        f"/api/v1/transactions/{first_id}/category",
        json={"category": "shopping", "apply_to_future": True},
        headers=headers,
    )
    assert patch_resp.status_code == 200
    updated = patch_resp.json()
    assert updated["category"] == "shopping"
    assert updated["category_source"] == "user"
    assert updated["category_confidence"] == 1.0


async def test_csv_statement_import(client: AsyncClient) -> None:
    token = await _get_auth_token(client)
    headers = {"Authorization": f"Bearer {token}"}

    accts_resp = await client.post("/api/v1/accounts/sync-mock", headers=headers)
    first_acct_id = accts_resp.json()[0]["id"]

    sample_csv = """Date,Narration,Amount,Direction,Currency
2026-10-25,UPI/1234/BLUE TOKAI COFFEE/BLR,380.00,debit,INR
2026-10-26,POS DMART GROCERY STORE,1450.50,debit,INR
2026-10-27,INTEREST CREDIT,240.00,credit,INR
"""
    import_resp = await client.post(
        "/api/v1/transactions/import-csv",
        json={"account_id": first_acct_id, "csv_content": sample_csv},
        headers=headers,
    )
    assert import_resp.status_code == 200
    res = import_resp.json()
    assert res["imported_count"] == 3


async def test_ml_insights_endpoint_full_pipeline(client: AsyncClient) -> None:
    token = await _get_auth_token(client)
    headers = {"Authorization": f"Bearer {token}"}

    # Insights automatically populates mock financial data if none exists
    resp = await client.get("/api/v1/insights", headers=headers)
    assert resp.status_code == 200
    data = resp.json()

    # 1. Hierarchical & Probabilistic forecast
    assert data["forecast_p10_minor"] <= data["forecast_p50_minor"] <= data["forecast_p90_minor"]
    assert len(data["category_forecasts"]) > 0

    # 2. Model comparison with MAPE/WAPE and 95% bootstrap intervals
    assert len(data["model_comparisons"]) >= 3
    selected = [m for m in data["model_comparisons"] if m["is_selected"]]
    assert len(selected) == 1

    # 3. High-speed C++ Monte Carlo runway & Wald analytical formula
    runway = data["runway"]
    assert "trajectory" in runway and len(runway["trajectory"]) > 0
    assert runway["mc_simulations_count"] == 10000
    assert 0.0 <= runway["overdraft_watch_probability_pct"] <= 100.0
    assert 0.0 <= runway["wald_analytical_probability_pct"] <= 100.0

    # 4. Java strict financial ledger verification & cryptographic SHA-256 seal
    java_audit = data["java_audit"]
    assert java_audit["is_valid"] is True
    assert len(java_audit["audit_seal_sha256"]) == 64
    assert java_audit["verified_debit_count"] > 0

    # 5. Behavioral clustering (KMeans + Silhouette + 2D PCA)
    assert data["optimal_k"] in (2, 3, 4)
    assert -1.0 <= data["silhouette_score"] <= 1.0
    assert len(data["clusters"]) > 0
    assert len(data["pca_samples"]) > 0

    # 6. Periodic recurring bills & anomalies
    assert len(data["recurring_bills"]) > 0
    assert len(data["anomalies"]) > 0

    # 7. Counterfactual savings and SHAP attributions
    assert len(data["savings_recommendations"]) > 0
    assert len(data["shap_attributions"]) >= 4

    # 8. Verify GET /api/v1/insights is side-effect free (did not insert phantom accounts)
    accts_after = await client.get("/api/v1/accounts", headers=headers)
    assert accts_after.status_code == 200
    assert accts_after.json() == []


async def test_csv_jpy_debit_credit_and_validation(client: AsyncClient) -> None:
    token = await _get_auth_token(client)
    headers = {"Authorization": f"Bearer {token}"}

    accts_resp = await client.post("/api/v1/accounts/sync-mock", headers=headers)
    first_acct_id = accts_resp.json()[0]["id"]

    # Separate Debit/Credit columns + JPY 0-decimal minor units + invalid rows skipped
    csv_payload = """Date,Narration,Debit,Credit,Currency
2026-10-20,TOKYO RAMEN BAR,1500,,JPY
2026-10-21,ACME PAYROLL BONUS,,75000,INR
not-a-date,BAD DATE ROW,500,,INR
2026-10-22,NON FINITE ROW,inf,,INR
2026-10-23,UNKNOWN CURRENCY ROW,100,,ZZZ
"""
    imp = await client.post(
        "/api/v1/transactions/import-csv",
        json={"account_id": first_acct_id, "csv_content": csv_payload},
        headers=headers,
    )
    assert imp.status_code == 200
    assert imp.json()["imported_count"] == 2

    txns = (await client.get("/api/v1/transactions?limit=500", headers=headers)).json()
    jpy_rows = [t for t in txns if "Ramen" in t["merchant_name"]]
    assert len(jpy_rows) == 1
    assert jpy_rows[0]["amount_minor"] == 1500
    assert jpy_rows[0]["currency"] == "JPY"

    # Verify recurring and anomaly flags are populated on synced transactions
    assert any(t["is_recurring"] for t in txns)
    assert any(t["is_anomaly"] for t in txns)

    # Verify category validation rejects unknown/injected categories
    tid = txns[0]["id"]
    bad_cat = await client.patch(
        f"/api/v1/transactions/{tid}/category",
        json={"category": "injected|category\n"},
        headers=headers,
    )
    assert bad_cat.status_code == 422

    # Verify switching base_currency re-converts stored transactions and scales runway threshold
    ins_inr = (await client.get("/api/v1/insights", headers=headers)).json()
    await client.patch("/api/v1/users/me", json={"base_currency": "USD"}, headers=headers)
    ins_usd = (await client.get("/api/v1/insights", headers=headers)).json()
    assert ins_usd["forecast_p50_minor"] < ins_inr["forecast_p50_minor"] / 20
    assert ins_usd["runway"]["safe_threshold_minor"] < 100000

    # Verify accounts return balance_base_minor converted to USD
    accts_usd = (await client.get("/api/v1/accounts", headers=headers)).json()
    assert all(a["balance_base_minor"] > 0 for a in accts_usd)


async def test_csv_import_without_existing_accounts(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/auth/dev-login",
        json={"email": "no_acct_csv@example.com", "client": "mobile"},
    )
    token = str(resp.json()["tokens"]["access_token"])
    headers = {"Authorization": f"Bearer {token}"}

    csv_text = "Date,Narration,Amount,Direction,Currency\n2026-10-25,UPI/SWIGGY ORDER,450.00,debit,INR\n"
    imp = await client.post(
        "/api/v1/transactions/import-csv",
        json={"account_id": None, "csv_content": csv_text},
        headers=headers,
    )
    assert imp.status_code == 200
    assert imp.json()["imported_count"] == 1
    accts = (await client.get("/api/v1/accounts", headers=headers)).json()
    assert len(accts) == 1
    assert accts[0]["name"] == "CSV Statement Account"
