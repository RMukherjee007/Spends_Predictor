import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import {
  AlertCircle,
  ArrowRight,
  Building2,
  CheckCircle2,
  CreditCard,
  Plus,
  RefreshCw,
  ShieldCheck,
  Wallet,
} from 'lucide-react'
import { api, type BankAccountRead } from '@/api/client'
import { useAuth } from '@/context/AuthContext'
import { formatMinorUnits } from '@/lib/money'

export function AccountsPage() {
  const { user } = useAuth()
  const baseCurrency = user?.base_currency ?? 'INR'
  const locale = user?.locale ?? 'en-IN'

  const [accounts, setAccounts] = useState<BankAccountRead[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [statusMessage, setStatusMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null)

  useEffect(() => {
    let ignore = false
    async function fetchAccounts() {
      const { data, error } = await api.GET('/api/v1/accounts')
      if (!ignore) {
        if (data && !error) {
          setAccounts(data)
        }
        setLoading(false)
      }
    }
    void fetchAccounts()
    return () => {
      ignore = true
    }
  }, [])

  async function handleSyncMock() {
    setSyncing(true)
    setStatusMessage(null)
    const { data, error } = await api.POST('/api/v1/accounts/sync-mock')
    setSyncing(false)
    if (data && !error) {
      setAccounts(data)
      setStatusMessage({
        text: `Successfully synced ${data.length} accounts with 6 months of historical transactions and ML telemetry.`,
        type: 'success',
      })
      setTimeout(() => setStatusMessage(null), 5000)
    } else {
      setStatusMessage({
        text: 'Failed to sync sandbox accounts. Please verify backend connection.',
        type: 'error',
      })
    }
  }

  // Calculate summary metrics across all currencies converted to baseCurrency
  const liquidAccounts = accounts.filter((a) =>
    ['checking', 'savings', 'wallet'].includes(a.account_type.toLowerCase()),
  )
  const creditAccounts = accounts.filter((a) =>
    ['credit', 'credit_card'].includes(a.account_type.toLowerCase()),
  )

  const totalLiquidBaseMinor = liquidAccounts.reduce(
    (acc, a) => acc + (a.balance_base_minor ?? a.balance_minor),
    0,
  )

  const totalCreditBaseMinor = creditAccounts.reduce(
    (acc, a) => acc + Math.abs(a.balance_base_minor ?? a.balance_minor),
    0,
  )

  return (
    <div className="space-y-8">
      {/* Header */}
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-medium uppercase tracking-wider text-muted">
            Banking &amp; Ledger Connections
          </p>
          <h1
            className="font-semibold tracking-tight text-fg"
            style={{ fontSize: 'clamp(1.5rem, 1.1rem + 1.5vw, 2.25rem)' }}
          >
            Connected Accounts
          </h1>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <button
            type="button"
            onClick={() => void handleSyncMock()}
            disabled={syncing}
            className="inline-flex items-center gap-2 rounded-xl bg-brand px-4 py-2.5 text-xs font-semibold text-brand-fg hover:opacity-90 disabled:opacity-50 transition"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${syncing ? 'animate-spin' : ''}`} aria-hidden="true" />
            <span>{syncing ? 'Syncing Sandbox…' : 'Sync Sandbox Bank'}</span>
          </button>
        </div>
      </header>

      {/* Status Alert */}
      {statusMessage && (
        <div
          role="status"
          aria-live="polite"
          className={`flex items-center gap-3 rounded-2xl border p-4 text-sm ${
            statusMessage.type === 'success'
              ? 'border-positive/30 bg-positive/10 text-positive'
              : 'border-danger/30 bg-danger/10 text-danger'
          }`}
        >
          {statusMessage.type === 'success' ? (
            <CheckCircle2 className="h-5 w-5 shrink-0" aria-hidden="true" />
          ) : (
            <AlertCircle className="h-5 w-5 shrink-0" aria-hidden="true" />
          )}
          <span>{statusMessage.text}</span>
        </div>
      )}

      {/* Summary Cards */}
      <section aria-label="Account Summary" className="@container">
        <div className="grid grid-cols-1 @min-[640px]:grid-cols-3 gap-4">
          <article className="rounded-2xl border border-line bg-surface p-5 space-y-1.5">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Liquid Assets ({baseCurrency})</span>
              <Wallet className="h-4 w-4 text-brand" aria-hidden="true" />
            </div>
            <p className="text-2xl font-semibold tracking-tight tabular">
              {formatMinorUnits(totalLiquidBaseMinor, baseCurrency, locale)}
            </p>
            <p className="text-xs text-muted">{liquidAccounts.length} deposit &amp; savings accounts</p>
          </article>

          <article className="rounded-2xl border border-line bg-surface p-5 space-y-1.5">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Credit Card Balance ({baseCurrency})</span>
              <CreditCard className="h-4 w-4 text-muted" aria-hidden="true" />
            </div>
            <p className="text-2xl font-semibold tracking-tight tabular">
              {formatMinorUnits(totalCreditBaseMinor, baseCurrency, locale)}
            </p>
            <p className="text-xs text-muted">{creditAccounts.length} active credit cards</p>
          </article>

          <article className="rounded-2xl border border-line bg-surface p-5 space-y-1.5">
            <div className="flex items-center justify-between text-xs text-muted">
              <span>Ledger Security &amp; Encryption</span>
              <ShieldCheck className="h-4 w-4 text-positive" aria-hidden="true" />
            </div>
            <p className="text-2xl font-semibold tracking-tight">AES-128-CBC</p>
            <p className="text-xs text-positive">Masked numbers encrypted at rest</p>
          </article>
        </div>
      </section>

      {/* Accounts List */}
      <section aria-labelledby="accounts-list-heading" className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 id="accounts-list-heading" className="text-base font-semibold tracking-tight">
            Active Financial Institutions ({accounts.length})
          </h2>
          <Link
            to="/transactions"
            className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline"
          >
            <span>View all transactions</span>
            <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
          </Link>
        </div>

        {loading ? (
          <div className="rounded-2xl border border-line bg-surface p-12 text-center text-sm text-muted">
            Loading connected accounts…
          </div>
        ) : accounts.length === 0 ? (
          <div className="rounded-2xl border border-line border-dashed bg-surface p-12 text-center space-y-3">
            <Building2 className="mx-auto h-8 w-8 text-muted" aria-hidden="true" />
            <div>
              <p className="text-sm font-medium text-fg">No accounts linked yet</p>
              <p className="text-xs text-muted max-w-sm mx-auto mt-1">
                Link sandbox banking data to populate 6 months of realistic transaction history, recurring subscriptions, and ML cashflow forecasting.
              </p>
            </div>
            <button
              type="button"
              onClick={() => void handleSyncMock()}
              className="inline-flex items-center gap-2 rounded-xl bg-brand px-4 py-2 text-xs font-semibold text-brand-fg"
            >
              <Plus className="h-4 w-4" aria-hidden="true" />
              <span>Link Sandbox Accounts</span>
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {accounts.map((acct) => (
              <article
                key={acct.id}
                className="flex flex-col justify-between rounded-2xl border border-line bg-surface p-5 space-y-4 shadow-sm hover:border-line-strong transition"
              >
                <div className="space-y-3">
                  <div className="flex items-start justify-between">
                    <div className="flex items-center gap-2.5">
                      <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-surface-muted text-fg">
                        {acct.account_type.toLowerCase().includes('credit') ? (
                          <CreditCard className="h-4 w-4" aria-hidden="true" />
                        ) : (
                          <Building2 className="h-4 w-4" aria-hidden="true" />
                        )}
                      </div>
                      <div>
                        <h3 className="text-sm font-semibold text-fg tracking-tight">{acct.name}</h3>
                        <p className="text-xs text-muted">{acct.institution}</p>
                      </div>
                    </div>
                    <span className="rounded-lg bg-surface-muted px-2 py-0.5 text-[11px] font-medium uppercase tracking-wider text-muted">
                      {acct.currency}
                    </span>
                  </div>

                  <div className="pt-2">
                    <p className="text-xs text-muted">Current Balance</p>
                    <p className="text-2xl font-bold tracking-tight text-fg tabular">
                      {formatMinorUnits(acct.balance_minor, acct.currency, locale)}
                    </p>
                    {acct.currency !== baseCurrency && acct.balance_base_minor != null && (
                      <p className="text-xs text-muted tabular">
                        &asymp; {formatMinorUnits(acct.balance_base_minor, baseCurrency, locale)}
                      </p>
                    )}
                  </div>
                </div>

                <div className="pt-3 border-t border-line flex items-center justify-between text-xs text-muted">
                  <span className="font-mono">{acct.masked_number ?? '•••• —'}</span>
                  <span>{acct.account_type.toUpperCase()}</span>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      {/* Integration Notes */}
      <section className="rounded-2xl border border-line bg-surface-muted/40 p-6 space-y-3">
        <h3 className="text-sm font-semibold tracking-tight">Open Banking &amp; Provider Architecture</h3>
        <p className="text-xs text-muted leading-relaxed">
          Spends Predictor abstracts bank connectors behind a strict interface (<code className="font-mono text-fg">BankConnector</code>). In development, the mock connector simulates Indian banking formats (HDFC, ICICI, SBI) and multi-currency accounts (Wise USD). In production, Account Aggregators (Setu, Finvu) or Plaid can be attached without altering ledger invariants.
        </p>
      </section>
    </div>
  )
}
