export interface paths {
  '/api/v1/health': {
    get: {
      responses: { 200: { content: { 'application/json': components['schemas']['HealthResponse'] } } }
    }
  }
  '/api/v1/health/ready': {
    get: {
      responses: {
        200: { content: { 'application/json': components['schemas']['HealthResponse'] } }
        503: { content: { 'application/json': components['schemas']['HealthResponse'] } }
      }
    }
  }
  '/api/v1/meta/currencies': {
    get: {
      responses: { 200: { content: { 'application/json': components['schemas']['CurrencyInfo'][] } } }
    }
  }
  '/api/v1/auth/login': {
    post: {
      requestBody: { content: { 'application/json': components['schemas']['LoginRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['AuthResponse'] } }
        401: { content: { 'application/json': components['schemas']['ErrorResponse'] } }
      }
    }
  }
  '/api/v1/auth/refresh': {
    post: {
      requestBody?: { content: { 'application/json': components['schemas']['RefreshRequest'] | null } }
      responses: {
        200: { content: { 'application/json': components['schemas']['AuthResponse'] } }
        401: { content: { 'application/json': components['schemas']['ErrorResponse'] } }
      }
    }
  }
  '/api/v1/auth/logout': {
    post: {
      requestBody?: { content: { 'application/json': components['schemas']['RefreshRequest'] | null } }
      responses: { 204: { content?: never } }
    }
  }
  '/api/v1/users/me': {
    get: {
      responses: { 200: { content: { 'application/json': components['schemas']['UserRead'] } } }
    }
    patch: {
      requestBody: { content: { 'application/json': components['schemas']['UserUpdate'] } }
      responses: { 200: { content: { 'application/json': components['schemas']['UserRead'] } } }
    }
  }
  '/api/v1/accounts': {
    get: {
      responses: { 200: { content: { 'application/json': components['schemas']['BankAccountRead'][] } } }
    }
  }
  '/api/v1/accounts/sync-mock': {
    post: {
      responses: { 200: { content: { 'application/json': components['schemas']['BankAccountRead'][] } } }
    }
  }
  '/api/v1/transactions': {
    get: {
      parameters: {
        query?: {
          limit?: number
          offset?: number
          category?: string | null
          account_id?: string | null
        }
      }
      responses: { 200: { content: { 'application/json': components['schemas']['TransactionRead'][] } } }
    }
  }
  '/api/v1/transactions/{transaction_id}/category': {
    patch: {
      parameters: { path: { transaction_id: string } }
      requestBody: { content: { 'application/json': components['schemas']['TransactionUpdateCategory'] } }
      responses: { 200: { content: { 'application/json': components['schemas']['TransactionRead'] } } }
    }
  }
  '/api/v1/transactions/import-csv': {
    post: {
      requestBody: { content: { 'application/json': components['schemas']['CsvImportRequest'] } }
      responses: { 200: { content: { 'application/json': components['schemas']['CsvImportResponse'] } } }
    }
  }
  '/api/v1/insights': {
    get: {
      responses: { 200: { content: { 'application/json': components['schemas']['MLInsightsResponse'] } } }
    }
  }
}

export interface components {
  schemas: {
    AnomalySchema: {
      transaction_id: string
      occurred_at: string
      merchant_name: string
      category: string
      amount_base_minor: number
      robust_zscore: number
      iforest_score: number
      combined_severity: string
      reason: string
    }
    AuthResponse: {
      user: components['schemas']['UserRead']
      tokens?: components['schemas']['TokenPair'] | null
    }
    BacktestPointSchema: {
      month: string
      actual_minor: number
      naive_pred_minor: number
      ets_pred_minor: number
      ml_pred_minor: number
    }
    BankAccountRead: {
      id: string
      name: string
      institution: string
      account_type: string
      currency: string
      balance_minor: number
      balance_base_minor?: number
      masked_number: string | null
      provider: string
      last_synced_at: string | null
      created_at: string
    }
    CashflowRunwaySchema: {
      current_liquid_balance_minor: number
      safe_threshold_minor: number
      overdraft_watch_probability_pct: number
      wald_analytical_probability_pct: number
      expected_days_above_threshold: number
      next_predicted_payday: string
      mc_execution_time_us: number
      mc_engine: string
      mc_simulations_count: number
      trajectory: components['schemas']['RunwayPointSchema'][]
    }
    CategoryForecastSchema: {
      category: string
      spent_so_far_minor: number
      p10_minor: number
      p50_minor: number
      p90_minor: number
      historical_monthly_mean_minor: number
      share_pct: number
    }
    ClusterSummarySchema: {
      cluster_id: number
      persona_name: string
      description: string
      transaction_share_pct: number
      avg_amount_minor: number
      weekend_ratio_pct: number
      late_night_ratio_pct: number
    }
    CsvImportRequest: {
      account_id?: string | null
      csv_content: string
    }
    CsvImportResponse: {
      imported_count: number
      skipped_count: number
    }
    CurrencyInfo: {
      code: string
      minor_units: number
    }
    ErrorDetail: {
      code: string
      message: string
      request_id?: string | null
      details?: Record<string, unknown>[] | null
    }
    ErrorResponse: {
      error: components['schemas']['ErrorDetail']
    }
    LoginRequest: {
      email: string
      password?: string | null
      full_name?: string | null
      client: 'web' | 'mobile'
    }
    HealthResponse: {
      status: 'ok' | 'degraded'
      version: string
      checks: Record<string, string>
    }
    JavaAuditSchema: {
      is_valid: boolean
      audit_seal_sha256: string
      verified_debit_count: number
      verified_credit_count: number
      total_debits_minor: number
      total_credits_minor: number
      net_cashflow_minor: number
      verifier_engine: string
    }
    LifestyleCreepSchema: {
      category: string
      early_weekly_avg_minor: number
      recent_weekly_avg_minor: number
      drift_pct: number
      cusum_stat: number
      status: string
    }
    MLInsightsResponse: {
      currency: string
      generated_at: string
      spent_this_month_minor: number
      remaining_recurring_minor: number
      forecast_p10_minor: number
      forecast_p50_minor: number
      forecast_p90_minor: number
      daily_velocity_minor: number
      category_forecasts: components['schemas']['CategoryForecastSchema'][]
      selected_model: string
      model_comparisons: components['schemas']['ModelComparisonSchema'][]
      backtest_series: components['schemas']['BacktestPointSchema'][]
      classifier_macro_f1: number
      user_rules_count: number
      recurring_bills: components['schemas']['RecurringBillSchema'][]
      anomalies: components['schemas']['AnomalySchema'][]
      lifestyle_creep: components['schemas']['LifestyleCreepSchema'][]
      runway: components['schemas']['CashflowRunwaySchema']
      java_audit: components['schemas']['JavaAuditSchema']
      optimal_k: number
      silhouette_score: number
      silhouette_by_k: Record<string, number>
      dominant_persona: string
      clusters: components['schemas']['ClusterSummarySchema'][]
      pca_samples: components['schemas']['PcaSamplePointSchema'][]
      savings_recommendations: components['schemas']['SavingsRecommendationSchema'][]
      shap_attributions: components['schemas']['ShapAttributionSchema'][]
    }
    ModelComparisonSchema: {
      model_name: string
      mape_pct: number
      wape_pct: number
      mae_minor: number
      ci95_low_pct: number
      ci95_high_pct: number
      is_selected: boolean
      notes: string
    }
    PcaSamplePointSchema: {
      pc1: number
      pc2: number
      cluster_id: number
      persona_name: string
      category: string
      merchant_name: string
      amount_base_minor: number
    }
    RecurringBillSchema: {
      merchant_name: string
      category: string
      cadence: string
      avg_interval_days: number
      typical_amount_minor: number
      latest_amount_minor: number
      price_creep_pct: number
      has_price_hike: boolean
      next_expected_date: string
      confidence: number
    }
    RefreshRequest: {
      refresh_token?: string | null
    }
    RunwayPointSchema: {
      day_offset: number
      date: string
      p10_balance_minor: number
      p50_balance_minor: number
      p90_balance_minor: number
    }
    SavingsRecommendationSchema: {
      category: string
      current_monthly_minor: number
      suggested_monthly_minor: number
      monthly_savings_minor: number
      annual_savings_minor: number
      elasticity_score: number
      action_tip: string
    }
    ShapAttributionSchema: {
      feature: string
      impact_minor: number
      direction: 'up' | 'down' | 'base'
      explanation: string
    }
    TokenPair: {
      access_token: string
      refresh_token: string
      token_type: 'bearer'
      expires_in: number
    }
    TransactionRead: {
      id: string
      account_id: string
      occurred_at: string
      direction: string
      amount_minor: number
      currency: string
      amount_base_minor: number
      fx_rate: number
      merchant_name: string
      category: string
      category_source: string
      category_confidence: number
      is_recurring: boolean
      is_anomaly: boolean
      anomaly_score: number
      anomaly_reason: string | null
    }
    TransactionUpdateCategory: {
      category:
        | 'dining'
        | 'groceries'
        | 'rent'
        | 'utilities'
        | 'transport'
        | 'shopping'
        | 'subscriptions'
        | 'healthcare'
        | 'travel'
        | 'entertainment'
        | 'investments'
        | 'salary'
        | 'other'
      apply_to_future: boolean
    }
    UserRead: {
      id: string
      email: string
      full_name: string | null
      avatar_url: string | null
      base_currency: string
      timezone: string
      locale: string
      is_admin: boolean
      created_at: string
    }
    UserUpdate: {
      full_name?: string | null
      base_currency?: string | null
      timezone?: string | null
      locale?: string | null
    }
  }
}
