from __future__ import annotations

from typing import Literal

from pydantic import BaseModel


class CategoryForecastSchema(BaseModel):
    category: str
    spent_so_far_minor: int
    p10_minor: int
    p50_minor: int
    p90_minor: int
    historical_monthly_mean_minor: int
    share_pct: float


class ModelComparisonSchema(BaseModel):
    model_name: str
    mape_pct: float
    wape_pct: float
    mae_minor: int
    ci95_low_pct: float
    ci95_high_pct: float
    is_selected: bool
    notes: str


class BacktestPointSchema(BaseModel):
    month: str
    actual_minor: int
    naive_pred_minor: int
    ets_pred_minor: int
    ml_pred_minor: int


class RecurringBillSchema(BaseModel):
    merchant_name: str
    category: str
    cadence: str
    avg_interval_days: float
    typical_amount_minor: int
    latest_amount_minor: int
    price_creep_pct: float
    has_price_hike: bool
    next_expected_date: str
    confidence: float


class AnomalySchema(BaseModel):
    transaction_id: str
    occurred_at: str
    merchant_name: str
    category: str
    amount_base_minor: int
    robust_zscore: float
    iforest_score: float
    combined_severity: str
    reason: str


class LifestyleCreepSchema(BaseModel):
    category: str
    early_weekly_avg_minor: int
    recent_weekly_avg_minor: int
    drift_pct: float
    cusum_stat: float
    status: str


class RunwayPointSchema(BaseModel):
    day_offset: int
    date: str
    p10_balance_minor: int
    p50_balance_minor: int
    p90_balance_minor: int


class CashflowRunwaySchema(BaseModel):
    current_liquid_balance_minor: int
    safe_threshold_minor: int
    overdraft_watch_probability_pct: float
    wald_analytical_probability_pct: float
    expected_days_above_threshold: int
    next_predicted_payday: str
    mc_execution_time_us: float
    mc_engine: str
    mc_simulations_count: int
    trajectory: list[RunwayPointSchema]


class JavaAuditSchema(BaseModel):
    is_valid: bool
    audit_seal_sha256: str
    verified_debit_count: int
    verified_credit_count: int
    total_debits_minor: int
    total_credits_minor: int
    net_cashflow_minor: int
    verifier_engine: str


class ClusterSummarySchema(BaseModel):
    cluster_id: int
    persona_name: str
    description: str
    transaction_share_pct: float
    avg_amount_minor: int
    weekend_ratio_pct: float
    late_night_ratio_pct: float


class PcaSamplePointSchema(BaseModel):
    pc1: float
    pc2: float
    cluster_id: int
    persona_name: str
    category: str
    merchant_name: str
    amount_base_minor: int


class SavingsRecommendationSchema(BaseModel):
    category: str
    current_monthly_minor: int
    suggested_monthly_minor: int
    monthly_savings_minor: int
    annual_savings_minor: int
    elasticity_score: float
    action_tip: str


class ShapAttributionSchema(BaseModel):
    feature: str
    impact_minor: int
    direction: Literal["up", "down", "base"]
    explanation: str


class MLInsightsResponse(BaseModel):
    currency: str
    generated_at: str
    spent_this_month_minor: int
    remaining_recurring_minor: int
    forecast_p10_minor: int
    forecast_p50_minor: int
    forecast_p90_minor: int
    daily_velocity_minor: int
    category_forecasts: list[CategoryForecastSchema]
    selected_model: str
    model_comparisons: list[ModelComparisonSchema]
    backtest_series: list[BacktestPointSchema]
    classifier_macro_f1: float
    user_rules_count: int
    recurring_bills: list[RecurringBillSchema]
    anomalies: list[AnomalySchema]
    lifestyle_creep: list[LifestyleCreepSchema]
    runway: CashflowRunwaySchema
    java_audit: JavaAuditSchema
    optimal_k: int
    silhouette_score: float
    silhouette_by_k: dict[int, float]
    dominant_persona: str
    clusters: list[ClusterSummarySchema]
    pca_samples: list[PcaSamplePointSchema]
    savings_recommendations: list[SavingsRecommendationSchema]
    shap_attributions: list[ShapAttributionSchema]
