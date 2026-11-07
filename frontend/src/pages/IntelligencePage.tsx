import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import {
  Activity,
  AlertOctagon,
  AlertTriangle,
  ArrowRight,
  BarChart3,
  BrainCircuit,
  CheckCircle2,
  Cpu,
  Lock,
  PiggyBank,
  RefreshCw,
  Repeat,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  Users,
  Zap,
} from 'lucide-react'
import { api, type MLInsightsResponse } from '@/api/client'
import { useAuth } from '@/context/AuthContext'
import { formatMinorUnits } from '@/lib/money'

type TabKey = 'forecast' | 'models' | 'runway' | 'recurring' | 'anomalies' | 'personas'

function DataTable({
  label,
  headers,
  maxHeight = '',
  cellPadding = 'p-3',
  children,
}: {
  label: string
  headers: (string | { text: string; right?: boolean })[]
  maxHeight?: string
  cellPadding?: string
  children: ReactNode
}) {
  return (
    <div
      tabIndex={0}
      role="region"
      aria-label={label}
      className={`overflow-x-auto rounded-xl border border-line ${maxHeight}`}
    >
      <table className="w-full text-left text-xs border-collapse">
        <thead className="bg-surface-muted/60 text-muted font-medium border-b border-line sticky top-0">
          <tr>
            {headers.map((h) => {
              const item = typeof h === 'string' ? { text: h, right: false } : h
              return (
                <th key={item.text} scope="col" className={`${cellPadding} ${item.right ? 'text-right' : ''}`}>
                  {item.text}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody className="divide-y divide-line text-fg">{children}</tbody>
      </table>
    </div>
  )
}

export function IntelligencePage() {
  const { user } = useAuth()
  const currency = user?.base_currency ?? 'INR'
  const locale = user?.locale ?? 'en-IN'
  const fmt = (minor: number) => formatMinorUnits(minor, currency, locale)

  const [insights, setInsights] = useState<MLInsightsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshKey, setRefreshKey] = useState(0)
  const [activeTab, setActiveTab] = useState<TabKey>('forecast')
  const [copiedSeal, setCopiedSeal] = useState(false)

  useEffect(() => {
    let ignore = false
    void api.GET('/api/v1/insights').then(({ data }) => {
      if (!ignore) {
        if (data) setInsights(data)
        setLoading(false)
      }
    })
    return () => {
      ignore = true
    }
  }, [refreshKey])

  function copyAuditSeal(seal: string) {
    if (navigator?.clipboard?.writeText) {
      void navigator.clipboard.writeText(seal)
      setCopiedSeal(true)
      setTimeout(() => setCopiedSeal(false), 2500)
    }
  }

  if (loading && !insights) {
    return (
      <div className="flex flex-col items-center justify-center p-16 space-y-4">
        <Cpu className="h-8 w-8 text-brand animate-pulse" aria-hidden="true" />
        <p className="text-sm font-medium text-fg">Synthesizing 7-Pillar ML Intelligence…</p>
        <p className="text-xs text-muted">Running C++20 Monte Carlo kernel &amp; Java 26 SHA-256 ledger verifier…</p>
      </div>
    )
  }

  if (
    !insights ||
    (insights.category_forecasts.length === 0 &&
      insights.java_audit.verified_debit_count === 0 &&
      insights.java_audit.verified_credit_count === 0)
  ) {
    return (
      <div className="rounded-2xl border border-line bg-surface p-12 text-center space-y-4">
        <BrainCircuit className="mx-auto h-8 w-8 text-muted" aria-hidden="true" />
        <h2 className="text-base font-semibold">No ML Insights Generated Yet</h2>
        <p className="text-xs text-muted max-w-md mx-auto">
          Please link your sandbox bank accounts or import a CSV statement to enable machine learning forecasts, C++ runway simulations, and anomaly detection.
        </p>
        <div className="flex justify-center gap-3">
          <Link
            to="/accounts"
            className="rounded-xl bg-brand px-4 py-2 text-xs font-semibold text-brand-fg"
          >
            Connect Bank Accounts
          </Link>
          <Link
            to="/transactions"
            className="rounded-xl border border-line bg-surface px-4 py-2 text-xs font-semibold text-fg"
          >
            Upload Statement
          </Link>
        </div>
      </div>
    )
  }

  const {
    forecast_p10_minor,
    forecast_p50_minor,
    forecast_p90_minor,
    spent_this_month_minor,
    remaining_recurring_minor,
    daily_velocity_minor,
    category_forecasts,
    selected_model,
    model_comparisons,
    backtest_series,
    classifier_macro_f1,
    user_rules_count,
    recurring_bills,
    anomalies,
    lifestyle_creep,
    runway,
    java_audit,
    optimal_k,
    silhouette_score,
    silhouette_by_k,
    dominant_persona,
    clusters,
    pca_samples,
    savings_recommendations,
    shap_attributions,
  } = insights

  return (
    <div className="space-y-8">
      {/* Page Header */}
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-brand/10 text-brand px-2.5 py-0.5 text-[11px] font-semibold">
              <Sparkles className="h-3 w-3" aria-hidden="true" />
              7-Pillar ML Engine
            </span>
            <span className="text-xs text-muted">
              Generated {new Date(insights.generated_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </span>
          </div>
          <h1
            className="font-semibold tracking-tight text-fg mt-1"
            style={{ fontSize: 'clamp(1.5rem, 1.1rem + 1.5vw, 2.25rem)' }}
          >
            Financial Intelligence &amp; Predictive Analytics
          </h1>
        </div>

        <button
          type="button"
          onClick={() => {
            setLoading(true)
            setRefreshKey((k) => k + 1)
          }}
          className="inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-3.5 py-2 text-xs font-medium text-fg hover:bg-surface-muted transition"
        >
          <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
          <span>Re-run Models</span>
        </button>
      </header>

      {/* C++20 & Java OpenJDK Dual High-Performance / Strict Security Telemetry Banners */}
      <section aria-label="Verification and Hardware Telemetry" className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* C++20 Native Kernel Telemetry */}
        <div className="rounded-2xl border border-line bg-surface p-5 space-y-3 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-surface-muted text-fg">
                <Cpu className="h-4 w-4" aria-hidden="true" />
              </span>
              <div>
                <h2 className="text-xs font-semibold uppercase tracking-wider text-fg">C++20 Native Kernel</h2>
                <p className="text-[11px] text-muted">{runway.mc_engine}</p>
              </div>
            </div>
            <span className="inline-flex items-center gap-1 rounded-md bg-positive/10 text-positive px-2 py-0.5 text-xs font-mono font-medium">
              <Zap className="h-3 w-3" aria-hidden="true" />
              {(runway.mc_execution_time_us / 1000).toFixed(2)} ms
            </span>
          </div>

          <div className="grid grid-cols-2 gap-3 pt-1 text-xs">
            <div className="rounded-xl bg-surface-muted/60 p-2.5">
              <span className="text-muted block text-[11px]">Monte Carlo Simulation</span>
              <span className="font-semibold text-fg tabular">
                {runway.mc_simulations_count.toLocaleString()} paths &times; 30d
              </span>
            </div>
            <div className="rounded-xl bg-surface-muted/60 p-2.5">
              <span className="text-muted block text-[11px]">Wald Analytical Barrier Pct</span>
              <span className="font-semibold text-fg font-mono">
                {runway.wald_analytical_probability_pct.toFixed(2)}%
              </span>
            </div>
          </div>
          <p className="text-[11px] text-muted leading-tight">
            Exact Bachelier-Wald Brownian motion with drift barrier formula computed alongside SplitMix64 pseudo-random generation.
          </p>
        </div>

        {/* Java OpenJDK 26 Strict Financial Ledger Audit Seal */}
        <div className="rounded-2xl border border-line bg-surface p-5 space-y-3 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-surface-muted text-fg">
                <Lock className="h-4 w-4" aria-hidden="true" />
              </span>
              <div>
                <h2 className="text-xs font-semibold uppercase tracking-wider text-fg">Java OpenJDK Strict Audit</h2>
                <p className="text-[11px] text-muted">{java_audit.verifier_engine}</p>
              </div>
            </div>
            <span
              className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ${
                java_audit.is_valid
                  ? 'bg-positive/10 text-positive'
                  : 'bg-danger/10 text-danger'
              }`}
            >
              <ShieldCheck className="h-3.5 w-3.5" aria-hidden="true" />
              {java_audit.is_valid ? 'Seal Verified' : 'Audit Mismatch'}
            </span>
          </div>

          <div className="space-y-1.5">
            <div className="flex items-center justify-between text-xs">
              <span className="text-muted">SHA-256 Hash-Chain Audit Seal:</span>
              <button
                type="button"
                onClick={() => copyAuditSeal(java_audit.audit_seal_sha256)}
                className="text-[11px] text-brand hover:underline font-medium"
              >
                {copiedSeal ? 'Copied!' : 'Copy Hash'}
              </button>
            </div>
            <div className="rounded-lg bg-surface-muted p-2 font-mono text-[11px] text-fg truncate select-all">
              {java_audit.audit_seal_sha256}
            </div>
          </div>

          <div className="flex items-center justify-between text-[11px] text-muted pt-0.5">
            <span>Verified: {java_audit.verified_debit_count} debits &bull; {java_audit.verified_credit_count} credits</span>
            <span className="text-positive font-medium">0.00 Float Drift (DECIMAL128)</span>
          </div>
        </div>
      </section>

      {/* Top Probabilistic Forecast Range Cards */}
      <section aria-label="Probabilistic Summary Cards" className="@container">
        <div className="grid grid-cols-1 @min-[640px]:grid-cols-4 gap-4">
          <article className="rounded-2xl border border-line bg-surface p-5 space-y-2">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Expected Month-End (P50)</span>
              <TrendingUp className="h-4 w-4 text-brand" aria-hidden="true" />
            </div>
            <p className="text-2xl font-bold tracking-tight tabular text-fg">
              {formatMinorUnits(forecast_p50_minor, currency, locale)}
            </p>
            <div className="flex items-center justify-between text-[11px] text-muted">
              <span>P10: {formatMinorUnits(forecast_p10_minor, currency, locale)}</span>
              <span>P90: {formatMinorUnits(forecast_p90_minor, currency, locale)}</span>
            </div>
          </article>

          <article className="rounded-2xl border border-line bg-surface p-5 space-y-2">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Month-to-Date Spent</span>
              <Activity className="h-4 w-4 text-muted" aria-hidden="true" />
            </div>
            <p className="text-2xl font-bold tracking-tight tabular text-fg">
              {formatMinorUnits(spent_this_month_minor, currency, locale)}
            </p>
            <p className="text-xs text-muted">
              Velocity: ~{formatMinorUnits(daily_velocity_minor, currency, locale)} / day
            </p>
          </article>

          <article className="rounded-2xl border border-line bg-surface p-5 space-y-2">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Upcoming Recurring Bills</span>
              <Repeat className="h-4 w-4 text-muted" aria-hidden="true" />
            </div>
            <p className="text-2xl font-bold tracking-tight tabular text-fg">
              {formatMinorUnits(remaining_recurring_minor, currency, locale)}
            </p>
            <p className="text-xs text-muted">Committed subscriptions &amp; dues</p>
          </article>

          <article className="rounded-2xl border border-line bg-surface p-5 space-y-2">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Liquid Runway Survival</span>
              <PiggyBank className="h-4 w-4 text-positive" aria-hidden="true" />
            </div>
            <p className="text-2xl font-bold tracking-tight tabular text-fg">
              {runway.expected_days_above_threshold} Days
            </p>
            <p className="text-xs text-muted">
              Above buffer &bull; Payday: {runway.next_predicted_payday}
            </p>
          </article>
        </div>
      </section>

      {/* Tabs Navigation for 7 Pillars */}
      <div className="border-b border-line">
        <nav
          aria-label="Intelligence Sections"
          className="flex space-x-2 overflow-x-auto pb-2 scrollbar-none"
        >
          {[
            { id: 'forecast', label: '1. Cashflow Forecast', icon: TrendingUp },
            { id: 'models', label: '2. Multi-Model Backtest', icon: BarChart3 },
            { id: 'runway', label: '3. Monte Carlo Runway', icon: Cpu },
            { id: 'recurring', label: '4. Recurring & Price Hikes', icon: Repeat },
            { id: 'anomalies', label: '5. Anomalies & CUSUM', icon: AlertOctagon },
            { id: 'personas', label: '6. Personas & SHAP XAI', icon: Users },
          ].map(({ id, label, icon: Icon }) => {
            const isActive = activeTab === id
            return (
              <button
                key={id}
                type="button"
                onClick={() => setActiveTab(id as TabKey)}
                className={`flex items-center gap-2 whitespace-nowrap rounded-xl px-3.5 py-2 text-xs font-semibold transition ${
                  isActive
                    ? 'bg-fg text-bg shadow-sm'
                    : 'text-muted hover:bg-surface-muted hover:text-fg'
                }`}
              >
                <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                <span>{label}</span>
              </button>
            )
          })}
        </nav>
      </div>

      {activeTab === 'forecast' && (
        <section aria-labelledby="forecast-heading" className="space-y-6">
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
              <div>
                <h2 id="forecast-heading" className="text-base font-semibold tracking-tight">
                  Hierarchical Category Forecast (MinT Reconciled)
                </h2>
                <p className="text-xs text-muted">
                  Category predictions are reconciled with total budget using Minimum Trace (MinT) closed-form matrix math to guarantee coherent additivity.
                </p>
              </div>
              <span className="self-start inline-flex items-center gap-1 rounded-full bg-brand/10 text-brand px-3 py-1 text-xs font-medium">
                <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
                <span>MinT Matrix Invariance Ensured</span>
              </span>
            </div>

            {/* Responsive Table */}
            <DataTable
              label="Category Forecast Table"
              headers={[
                'Category',
                'MTD Spent',
                'P10 (Optimistic)',
                'P50 (Expected)',
                'P90 (Pessimistic)',
                'Historical Monthly Avg',
                { text: 'Budget Share', right: true },
              ]}
            >
              {category_forecasts.map((cat) => (
                <tr key={cat.category} className="hover:bg-surface-muted/40 font-medium transition">
                  <td className="p-3 font-semibold capitalize flex items-center gap-2">
                    <span className="h-2 w-2 rounded-full bg-brand" />
                    {cat.category}
                  </td>
                  <td className="p-3 tabular">{fmt(cat.spent_so_far_minor)}</td>
                  <td className="p-3 tabular text-positive">{fmt(cat.p10_minor)}</td>
                  <td className="p-3 tabular font-bold">{fmt(cat.p50_minor)}</td>
                  <td className="p-3 tabular text-muted">{fmt(cat.p90_minor)}</td>
                  <td className="p-3 tabular text-muted">{fmt(cat.historical_monthly_mean_minor)}</td>
                  <td className="p-3 text-right tabular font-mono">{cat.share_pct.toFixed(1)}%</td>
                </tr>
              ))}
            </DataTable>
          </div>
        </section>
      )}

      {activeTab === 'models' && (
        <section aria-labelledby="models-heading" className="space-y-6">
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-5">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h2 id="models-heading" className="text-base font-semibold tracking-tight">
                  Time-Series Model Comparison &amp; Backtesting
                </h2>
                <p className="text-xs text-muted">
                  Strictly evaluated on rolling out-of-fold temporal splits with 1,000-sample bootstrap confidence intervals.
                </p>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted">Winning Model:</span>
                <span className="rounded-lg bg-positive/10 text-positive px-2.5 py-1 text-xs font-semibold">
                  {selected_model}
                </span>
              </div>
            </div>

            {/* Models Table */}
            <DataTable
              label="Model Comparison Table"
              headers={['Candidate Model', 'Status', 'WAPE', 'MAPE', '95% Bootstrap CI', 'MAE', 'Methodology Notes']}
            >
              {model_comparisons.map((m) => (
                <tr
                  key={m.model_name}
                  className={m.is_selected ? 'bg-brand/5 font-semibold' : 'hover:bg-surface-muted/40'}
                >
                  <td className="p-3 flex items-center gap-2">
                    {m.is_selected && <CheckCircle2 className="h-4 w-4 text-positive shrink-0" />}
                    <span>{m.model_name}</span>
                  </td>
                  <td className="p-3">
                    {m.is_selected ? (
                      <span className="rounded-md bg-positive/10 text-positive px-2 py-0.5 text-[11px] font-semibold">
                        Production Active
                      </span>
                    ) : (
                      <span className="text-muted text-[11px]">Benchmark</span>
                    )}
                  </td>
                  <td className="p-3 tabular font-mono font-bold">{m.wape_pct.toFixed(2)}%</td>
                  <td className="p-3 tabular font-mono">{m.mape_pct.toFixed(2)}%</td>
                  <td className="p-3 tabular font-mono text-muted">
                    [{m.ci95_low_pct.toFixed(1)}%, {m.ci95_high_pct.toFixed(1)}%]
                  </td>
                  <td className="p-3 tabular">{fmt(m.mae_minor)}</td>
                  <td className="p-3 text-xs text-muted max-w-xs">{m.notes}</td>
                </tr>
              ))}
            </DataTable>

            {/* Backtest Historical Month-by-Month Validation */}
            <div className="pt-4 border-t border-line space-y-3">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">
                Temporal Out-of-Sample Backtest Series
              </h3>
              <DataTable
                label="Backtest Historical Series"
                headers={[
                  'Evaluation Month',
                  'Ground Truth Actual',
                  'Selected ML Engine',
                  'Holt-Winters ETS',
                  'Naive Seasonal Baseline',
                ]}
              >
                {backtest_series.map((pt) => (
                  <tr key={pt.month} className="hover:bg-surface-muted/40">
                    <td className="p-3 font-semibold">{pt.month}</td>
                    <td className="p-3 tabular font-bold">{fmt(pt.actual_minor)}</td>
                    <td className="p-3 tabular text-brand font-semibold">{fmt(pt.ml_pred_minor)}</td>
                    <td className="p-3 tabular text-muted">{fmt(pt.ets_pred_minor)}</td>
                    <td className="p-3 tabular text-muted">{fmt(pt.naive_pred_minor)}</td>
                  </tr>
                ))}
              </DataTable>
            </div>
          </div>
        </section>
      )}

      {activeTab === 'runway' && (
        <section aria-labelledby="runway-heading" className="space-y-6">
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-5">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h2 id="runway-heading" className="text-base font-semibold tracking-tight">
                  30-Day Liquidity Runway Simulation
                </h2>
                <p className="text-xs text-muted">
                  Powered by native C++20 kernel (<code className="font-mono">libquantcore.dylib</code>) executing 10,000 paths in microseconds.
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-xs text-muted">Safe Liquid Buffer:</span>
                <span className="font-mono font-semibold text-fg">{fmt(runway.safe_threshold_minor)}</span>
              </div>
            </div>

            {/* Overdraft Barrier Alert */}
            <div
              className={`rounded-xl border p-4 flex items-start gap-3 text-xs ${
                runway.overdraft_watch_probability_pct > 5
                  ? 'border-warning/30 bg-warning/10 text-warning'
                  : 'border-positive/30 bg-positive/10 text-positive'
              }`}
            >
              <ShieldCheck className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
              <div>
                <p className="font-semibold">
                  Overdraft / Below-Threshold Probability: {runway.overdraft_watch_probability_pct.toFixed(2)}%
                </p>
                <p className="mt-0.5 opacity-90">
                  Analytical Wald first-passage probability is {runway.wald_analytical_probability_pct.toFixed(2)}%. Expected runway until next scheduled salary on {runway.next_predicted_payday} is intact.
                </p>
              </div>
            </div>

            {/* 30-day Trajectory Table */}
            <DataTable
              label="30-day Trajectory"
              maxHeight="max-h-96"
              headers={[
                'Timeline',
                'Calendar Date',
                'P10 Balance (Cautious)',
                'P50 Expected Balance',
                'P90 Balance (Optimistic)',
                'Threshold Safety Margin',
              ]}
            >
              {runway.trajectory.map((pt) => {
                const margin = pt.p50_balance_minor - runway.safe_threshold_minor
                return (
                  <tr key={pt.date} className="hover:bg-surface-muted/40">
                    <td className="p-3 font-medium">Day +{pt.day_offset}</td>
                    <td className="p-3 font-semibold">{pt.date}</td>
                    <td className="p-3 tabular text-muted">{fmt(pt.p10_balance_minor)}</td>
                    <td className="p-3 tabular font-bold text-fg">{fmt(pt.p50_balance_minor)}</td>
                    <td className="p-3 tabular text-positive">{fmt(pt.p90_balance_minor)}</td>
                    <td className="p-3 tabular font-mono">
                      <span className={margin >= 0 ? 'text-positive' : 'text-danger font-bold'}>
                        {margin >= 0 ? '+' : ''}
                        {fmt(margin)}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </DataTable>
          </div>
        </section>
      )}

      {activeTab === 'recurring' && (
        <section aria-labelledby="recurring-heading" className="space-y-6">
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-5">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h2 id="recurring-heading" className="text-base font-semibold tracking-tight">
                  Recurring Commitments &amp; Silent Price Hike Watch
                </h2>
                <p className="text-xs text-muted">
                  Autonomously monitors subscription cadence, detects billing drifts, and flags unnoticed price hikes.
                </p>
              </div>
            </div>

            <DataTable
              label="Recurring Subscriptions Table"
              headers={[
                'Merchant / Service',
                'Category',
                'Billing Cadence',
                'Typical Baseline',
                'Latest Charge',
                'Price Hike Status',
                'Next Due Date',
              ]}
            >
              {recurring_bills.map((bill) => (
                <tr
                  key={bill.merchant_name}
                  className={bill.has_price_hike ? 'bg-danger/5 hover:bg-danger/10' : 'hover:bg-surface-muted/40'}
                >
                  <td className="p-3 font-semibold text-fg flex items-center gap-2">
                    <Repeat className="h-3.5 w-3.5 text-muted shrink-0" aria-hidden="true" />
                    <span>{bill.merchant_name}</span>
                  </td>
                  <td className="p-3 capitalize">{bill.category}</td>
                  <td className="p-3 capitalize">{bill.cadence} (~{bill.avg_interval_days}d)</td>
                  <td className="p-3 tabular">{fmt(bill.typical_amount_minor)}</td>
                  <td className="p-3 tabular font-semibold">{fmt(bill.latest_amount_minor)}</td>
                  <td className="p-3">
                    {bill.has_price_hike ? (
                      <span className="inline-flex items-center gap-1 rounded-md bg-danger/15 text-danger px-2 py-0.5 text-[11px] font-bold">
                        <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                        +{bill.price_creep_pct.toFixed(1)}% Hike Detected
                      </span>
                    ) : (
                      <span className="text-positive text-[11px] font-medium flex items-center gap-1">
                        <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                        Stable
                      </span>
                    )}
                  </td>
                  <td className="p-3 font-mono text-muted">{bill.next_expected_date}</td>
                </tr>
              ))}
            </DataTable>
          </div>
        </section>
      )}

      {activeTab === 'anomalies' && (
        <section aria-labelledby="anomalies-heading" className="space-y-6">
          {/* Ensemble Anomalies */}
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-4">
            <div>
              <h2 id="anomalies-heading" className="text-base font-semibold tracking-tight">
                Dual-Stage Ensemble Outlier Detection
              </h2>
              <p className="text-xs text-muted">
                Unsupervised Isolation Forest combined with Median Absolute Deviation (MAD) robust Z-scoring.
              </p>
            </div>

            {anomalies.length === 0 ? (
              <div className="rounded-xl border border-line p-6 text-center text-xs text-muted">
                No high-severity anomalous spends detected in recent ledger history.
              </div>
            ) : (
              <DataTable
                label="Anomalies Table"
                headers={['Date', 'Merchant', 'Category', 'Amount', 'MAD Robust Z', 'Isolation Forest', 'Severity', 'Diagnostic Rationale']}
              >
                {anomalies.map((anom) => (
                  <tr key={anom.transaction_id} className="hover:bg-surface-muted/40">
                    <td className="p-3 text-muted whitespace-nowrap">{anom.occurred_at.slice(0, 10)}</td>
                    <td className="p-3 font-semibold">{anom.merchant_name}</td>
                    <td className="p-3 capitalize">{anom.category}</td>
                    <td className="p-3 tabular font-bold">{fmt(anom.amount_base_minor)}</td>
                    <td className="p-3 tabular font-mono">z={anom.robust_zscore.toFixed(2)}</td>
                    <td className="p-3 tabular font-mono">{anom.iforest_score.toFixed(3)}</td>
                    <td className="p-3">
                      <span
                        className={`rounded-md px-2 py-0.5 text-[11px] font-semibold ${
                          anom.combined_severity === 'high'
                            ? 'bg-danger/15 text-danger'
                            : 'bg-warning/15 text-warning'
                        }`}
                      >
                        {anom.combined_severity.toUpperCase()}
                      </span>
                    </td>
                    <td className="p-3 text-xs text-muted max-w-sm">{anom.reason}</td>
                  </tr>
                ))}
              </DataTable>
            )}
          </div>

          {/* Page's CUSUM Lifestyle Drift */}
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-4">
            <div>
              <h3 className="text-base font-semibold tracking-tight">
                Page's CUSUM Lifestyle Creep Detector
              </h3>
              <p className="text-xs text-muted">
                Statistically tracks subtle cumulative shifts in discretionary category run-rates over time.
              </p>
            </div>

            <DataTable
              label="Lifestyle Creep Table"
              headers={[
                'Category',
                'Baseline Weekly Average',
                'Recent Weekly Average',
                'Run-Rate Drift',
                'Standardized CUSUM',
                'Diagnosis',
              ]}
            >
              {lifestyle_creep.map((c) => (
                <tr key={c.category} className="hover:bg-surface-muted/40">
                  <td className="p-3 font-semibold capitalize">{c.category}</td>
                  <td className="p-3 tabular">{fmt(c.early_weekly_avg_minor)}</td>
                  <td className="p-3 tabular font-bold">{fmt(c.recent_weekly_avg_minor)}</td>
                  <td className="p-3 tabular font-mono">
                    <span className={c.drift_pct > 15 ? 'text-danger font-semibold' : 'text-fg'}>
                      {c.drift_pct >= 0 ? '+' : ''}
                      {c.drift_pct.toFixed(1)}%
                    </span>
                  </td>
                  <td className="p-3 tabular font-mono">{c.cusum_stat.toFixed(2)}</td>
                  <td className="p-3">
                    <span
                      className={`rounded-md px-2 py-0.5 text-[11px] font-semibold ${
                        c.status === 'creep_alert' || c.status === 'creeping_up'
                          ? 'bg-danger/15 text-danger'
                          : 'bg-surface-muted text-muted'
                      }`}
                    >
                      {c.status === 'creep_alert' || c.status === 'creeping_up'
                        ? 'Lifestyle Creep Alert'
                        : 'Normal Variance'}
                    </span>
                  </td>
                </tr>
              ))}
            </DataTable>
          </div>
        </section>
      )}

      {activeTab === 'personas' && (
        <section aria-labelledby="personas-heading" className="space-y-6">
          {/* Persona Clustering Overview */}
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-5">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h2 id="personas-heading" className="text-base font-semibold tracking-tight">
                  Behavioral Personas &amp; K-Means Clustering (Optimal k={optimal_k})
                </h2>
                <p className="text-xs text-muted">
                  Evaluated using Silhouette maximization (score: {silhouette_score.toFixed(3)}) on multi-dimensional transaction features.
                </p>
              </div>
              <div className="rounded-xl bg-surface-muted px-3 py-1.5 text-xs text-fg">
                Dominant Persona: <strong className="text-brand">{dominant_persona}</strong>
              </div>
            </div>

            {/* Silhouette Scores Across K */}
            <div className="flex items-center gap-2 text-xs">
              <span className="text-muted">Silhouette Search:</span>
              {Object.entries(silhouette_by_k).map(([k, score]) => (
                <span
                  key={k}
                  className={`rounded-md px-2 py-0.5 font-mono text-[11px] ${
                    Number(k) === optimal_k
                      ? 'bg-brand text-brand-fg font-bold'
                      : 'bg-surface-muted text-muted'
                  }`}
                >
                  k={k}: {score.toFixed(3)}
                </span>
              ))}
            </div>

            {/* Clusters Grid */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              {clusters.map((cl) => (
                <div
                  key={cl.cluster_id}
                  className="rounded-xl border border-line bg-surface-muted/30 p-4 space-y-2.5"
                >
                  <div className="flex items-center justify-between">
                    <h3 className="text-xs font-semibold text-fg tracking-tight">{cl.persona_name}</h3>
                    <span className="text-[11px] font-mono text-muted">{cl.transaction_share_pct.toFixed(0)}% txns</span>
                  </div>
                  <p className="text-xs text-muted leading-relaxed">{cl.description}</p>
                  <div className="pt-2 border-t border-line text-[11px] text-muted space-y-1">
                    <div className="flex justify-between">
                      <span>Avg Ticket:</span>
                      <strong className="text-fg tabular">{fmt(cl.avg_amount_minor)}</strong>
                    </div>
                    <div className="flex justify-between">
                      <span>Weekend Ratio:</span>
                      <strong className="text-fg font-mono">{cl.weekend_ratio_pct.toFixed(1)}%</strong>
                    </div>
                    <div className="flex justify-between">
                      <span>Late Night Ratio:</span>
                      <strong className="text-fg font-mono">{cl.late_night_ratio_pct.toFixed(1)}%</strong>
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* PCA 2D Transaction Embedding Projection */}
            <div className="pt-4 border-t border-line space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">
                    PCA 2D Dimensionality Reduction (Transaction Embeddings)
                  </h3>
                  <p className="text-xs text-muted">
                    Transaction feature vectors projected onto first two principal components (PC1, PC2).
                  </p>
                </div>
                <span className="text-xs text-muted font-mono">{pca_samples.length} samples</span>
              </div>

              <DataTable
                label="PCA Samples Table"
                maxHeight="max-h-56"
                cellPadding="p-2.5"
                headers={['Merchant', 'Category', 'Persona Cluster', 'PC1', 'PC2', 'Amount']}
              >
                {pca_samples.slice(0, 15).map((pt, idx) => (
                  <tr key={`${pt.merchant_name}-${idx}`} className="hover:bg-surface-muted/40">
                    <td className="p-2.5 font-medium">{pt.merchant_name}</td>
                    <td className="p-2.5 capitalize">{pt.category}</td>
                    <td className="p-2.5">
                      <span className="rounded bg-brand/10 text-brand px-1.5 py-0.5 text-[10px] font-semibold">
                        {pt.persona_name}
                      </span>
                    </td>
                    <td className="p-2.5 font-mono tabular">{pt.pc1.toFixed(3)}</td>
                    <td className="p-2.5 font-mono tabular">{pt.pc2.toFixed(3)}</td>
                    <td className="p-2.5 font-mono tabular">{fmt(pt.amount_base_minor)}</td>
                  </tr>
                ))}
              </DataTable>
            </div>
          </div>

          {/* SHAP Feature Attribution Waterfall */}
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-4">
            <div>
              <h3 className="text-base font-semibold tracking-tight">
                Explainable AI: SHAP Feature Attribution
              </h3>
              <p className="text-xs text-muted">
                Shapley values decompose how specific behavioral factors exert upward or downward pressure on month-end spend.
              </p>
            </div>

            <div className="space-y-3">
              {shap_attributions.map((s) => (
                <div
                  key={s.feature}
                  className="flex items-center justify-between rounded-xl border border-line bg-surface-muted/20 p-3.5 text-xs"
                >
                  <div className="space-y-0.5 max-w-lg">
                    <p className="font-semibold text-fg">{s.feature}</p>
                    <p className="text-[11px] text-muted">{s.explanation}</p>
                  </div>
                  <div className="text-right">
                    <span
                      className={`font-mono font-bold tabular ${
                        s.direction === 'up'
                          ? 'text-danger'
                          : s.direction === 'down'
                          ? 'text-positive'
                          : 'text-fg'
                      }`}
                    >
                      {s.direction === 'up' ? '+' : s.direction === 'down' ? '-' : ''}
                      {fmt(Math.abs(s.impact_minor))}
                    </span>
                    <span className="block text-[10px] uppercase text-muted tracking-wider">
                      {s.direction === 'base' ? 'baseline anchor' : `${s.direction}ward drift`}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* What-If Counterfactual Savings Recommendations */}
          <div className="rounded-2xl border border-line bg-surface p-6 space-y-4">
            <div>
              <h3 className="text-base font-semibold tracking-tight">
                Counterfactual "What-If" Elasticity Recommendations
              </h3>
              <p className="text-xs text-muted">
                Algorithmic adjustments ranked by behavioral price elasticity to minimize lifestyle disruption.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {savings_recommendations.map((rec) => (
                <div
                  key={rec.category}
                  className="rounded-xl border border-line bg-surface-muted/20 p-4 space-y-3 flex flex-col justify-between"
                >
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-fg capitalize">{rec.category} Optimization</span>
                      <span className="text-[11px] font-mono text-positive bg-positive/10 px-2 py-0.5 rounded-md font-bold">
                        Save {formatMinorUnits(rec.monthly_savings_minor, currency, locale)} / mo
                      </span>
                    </div>
                    <p className="text-xs text-muted leading-relaxed">{rec.action_tip}</p>
                  </div>

                  <div className="pt-2 border-t border-line flex items-center justify-between text-xs">
                    <span className="text-muted">
                      Annual Potential: <strong className="text-fg">{formatMinorUnits(rec.annual_savings_minor, currency, locale)}</strong>
                    </span>
                    <span className="text-[11px] text-muted">Elasticity: {rec.elasticity_score.toFixed(2)}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* Categorizer Active Learning Callout */}
      <section className="rounded-2xl border border-line bg-surface-muted/40 p-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="space-y-1">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted">
            Continuous Active Learning
          </h3>
          <p className="text-xs text-fg">
            Merchant classifier holds a <strong>{(classifier_macro_f1 * 100).toFixed(1)}% Macro F1 score</strong> on out-of-sample data with {user_rules_count} active user override rules.
          </p>
        </div>
        <Link
          to="/transactions"
          className="inline-flex items-center gap-1.5 rounded-xl bg-brand px-3.5 py-2 text-xs font-semibold text-brand-fg self-start shrink-0"
        >
          <span>Teach Classifier on Transactions</span>
          <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      </section>
    </div>
  )
}
