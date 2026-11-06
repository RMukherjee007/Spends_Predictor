import { useEffect, useState, type ChangeEvent } from 'react'
import {
  AlertCircle,
  ArrowDownLeft,
  ArrowUpRight,
  CheckCircle2,
  Filter,
  Search,
  Upload,
} from 'lucide-react'
import {
  api,
  type BankAccountRead,
  type TransactionRead,
} from '@/api/client'
import { useAuth } from '@/context/AuthContext'
import { formatMinorUnits } from '@/lib/money'

const ALL_CATEGORIES = [
  'dining',
  'groceries',
  'rent',
  'utilities',
  'transport',
  'shopping',
  'subscriptions',
  'healthcare',
  'travel',
  'entertainment',
  'investments',
  'salary',
  'other',
] as const

type CategoryOption = (typeof ALL_CATEGORIES)[number]

export function TransactionsPage() {
  const { user } = useAuth()
  const currency = user?.base_currency ?? 'INR'
  const locale = user?.locale ?? 'en-IN'

  const [transactions, setTransactions] = useState<TransactionRead[]>([])
  const [accounts, setAccounts] = useState<BankAccountRead[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [selectedCategory, setSelectedCategory] = useState<string>('all')
  const [statusMsg, setStatusMsg] = useState<{ text: string; isError?: boolean } | null>(null)

  // CSV Import State
  const [showUpload, setShowUpload] = useState(false)
  const [uploadAccount, setUploadAccount] = useState<string>('')
  const [csvContent, setCsvContent] = useState('')
  const [importing, setImporting] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    let ignore = false
    void Promise.all([
      api.GET('/api/v1/transactions', { params: { query: { limit: 200 } } }),
      api.GET('/api/v1/accounts'),
    ]).then(([txnsRes, acctsRes]) => {
      if (!ignore) {
        if (txnsRes.data) setTransactions(txnsRes.data)
        if (acctsRes.data) {
          setAccounts(acctsRes.data)
          setUploadAccount((prev) => prev || (acctsRes.data?.[0]?.id ?? ''))
        }
        setLoading(false)
      }
    })
    return () => {
      ignore = true
    }
  }, [refreshKey])

  async function handleCategoryChange(txnId: string, newCat: CategoryOption) {
    const { data, error } = await api.PATCH('/api/v1/transactions/{transaction_id}/category', {
      params: { path: { transaction_id: txnId } },
      body: { category: newCat, apply_to_future: true },
    })
    if (data && !error) {
      setTransactions((prev) =>
        prev.map((t) =>
          t.id === txnId
            ? data
            : t.merchant_name === data.merchant_name
              ? { ...t, category: data.category, category_source: 'user', category_confidence: 1 }
              : t,
        ),
      )
      setStatusMsg({ text: `Category updated to "${newCat}". Active-learning rule saved!` })
      setTimeout(() => setStatusMsg(null), 4000)
    }
  }

  async function handleCsvSubmit() {
    if (!csvContent.trim()) return
    setImporting(true)
    const { data, error } = await api.POST('/api/v1/transactions/import-csv', {
      body: { account_id: uploadAccount || null, csv_content: csvContent },
    })
    setImporting(false)
    if (data && !error) {
      setStatusMsg({ text: `Successfully imported ${data.imported_count} new transactions!` })
      setShowUpload(false)
      setCsvContent('')
      setLoading(true)
      setRefreshKey((k) => k + 1)
    } else {
      setStatusMsg({ text: 'Failed to import CSV statement', isError: true })
    }
  }

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) {
      const reader = new FileReader()
      reader.onload = (evt) => {
        setCsvContent(evt.target?.result as string)
      }
      reader.readAsText(file)
    }
  }

  const filtered = transactions.filter((t) => {
    const matchesCat = selectedCategory === 'all' || t.category === selectedCategory
    const q = search.toLowerCase()
    const matchesSearch =
      !q ||
      t.merchant_name.toLowerCase().includes(q) ||
      t.category.toLowerCase().includes(q) ||
      t.direction.toLowerCase().includes(q)
    return matchesCat && matchesSearch
  })

  return (
    <div className="space-y-6">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-fg">Transactions Ledger</h1>
          <p className="text-xs text-muted">
            Multi-currency transactions categorized by rules and calibrated ML with per-user active
            learning.
          </p>
        </div>

        <button
          type="button"
          onClick={() => setShowUpload(!showUpload)}
          className="inline-flex items-center gap-2 rounded-xl bg-brand px-3.5 py-2 text-xs font-semibold text-brand-fg hover:opacity-95 transition-opacity self-start sm:self-auto cursor-pointer"
        >
          <Upload className="h-3.5 w-3.5" aria-hidden="true" />
          <span>Import Statement CSV</span>
        </button>
      </header>

      {statusMsg && (
        <div
          role="status"
          className={`flex items-center gap-2 rounded-xl border bg-surface p-3 text-xs ${
            statusMsg.isError ? 'border-danger text-danger' : 'border-positive text-positive'
          }`}
        >
          {statusMsg.isError ? (
            <AlertCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
          ) : (
            <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />
          )}
          <span>{statusMsg.text}</span>
        </div>
      )}

      {showUpload && (
        <section
          aria-label="Upload CSV Statement"
          className="rounded-2xl border border-line bg-surface p-5 space-y-4"
        >
          <h2 className="text-sm font-semibold text-fg">Import Statement (CSV)</h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <label htmlFor="csv-account-select" className="text-xs font-medium text-fg">Target Account</label>
              <select
                id="csv-account-select"
                aria-label="Target Account"
                value={uploadAccount}
                onChange={(e) => setUploadAccount(e.target.value)}
                className="w-full rounded-xl border border-line-strong bg-bg px-3 py-2 text-xs text-fg"
              >
                {accounts.length === 0 ? (
                  <option value="">Default CSV Statement Account ({currency})</option>
                ) : (
                  accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name} ({a.currency})
                    </option>
                  ))
                )}
              </select>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="csv-file-input" className="text-xs font-medium text-fg">Choose CSV File</label>
              <input
                id="csv-file-input"
                type="file"
                accept=".csv"
                onChange={handleFileChange}
                className="w-full text-xs text-muted file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-surface-muted file:text-fg hover:file:opacity-90"
              />
            </div>
          </div>

          <div className="flex gap-2 justify-end pt-2">
            <button
              type="button"
              onClick={() => setShowUpload(false)}
              className="rounded-xl border border-line px-3 py-1.5 text-xs font-medium text-muted hover:text-fg"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={importing || !csvContent}
              onClick={() => void handleCsvSubmit()}
              className="rounded-xl bg-brand px-4 py-1.5 text-xs font-semibold text-brand-fg disabled:opacity-50"
            >
              {importing ? 'Processing…' : 'Parse & Import'}
            </button>
          </div>
        </section>
      )}

      {/* Filter and Search Bar */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted" aria-hidden="true" />
          <input
            type="search"
            placeholder="Search merchant or category…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-xl border border-line-strong bg-surface pl-9 pr-3.5 py-2 text-xs text-fg placeholder:text-muted"
          />
        </div>

        <div className="flex items-center gap-2">
          <Filter className="h-4 w-4 text-muted shrink-0" aria-hidden="true" />
          <select
            aria-label="Filter by category"
            value={selectedCategory}
            onChange={(e) => setSelectedCategory(e.target.value)}
            className="rounded-xl border border-line-strong bg-surface px-3 py-2 text-xs text-fg"
          >
            <option value="all">All Categories ({transactions.length})</option>
            {ALL_CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>
                {cat.charAt(0).toUpperCase() + cat.slice(1)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Responsive Data Table */}
      <div
        tabIndex={0}
        role="region"
        aria-label="Transaction table"
        className="rounded-2xl border border-line bg-surface overflow-x-auto"
      >
        <table className="w-full text-left border-collapse text-xs">
          <thead>
            <tr className="border-b border-line bg-surface-muted text-muted sticky top-0 z-10">
              <th className="py-3 px-4 font-medium">Date</th>
              <th className="py-3 px-4 font-medium">Merchant / Details</th>
              <th className="py-3 px-4 font-medium">Category (Active Learning)</th>
              <th className="py-3 px-4 font-medium">Source / ML Conf</th>
              <th className="py-3 px-4 font-medium text-right">Amount (Base {currency})</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {loading ? (
              <tr>
                <td colSpan={5} className="py-8 text-center text-muted">
                  Loading transactions…
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={5} className="py-8 text-center text-muted">
                  No matching transactions found.
                </td>
              </tr>
            ) : (
              filtered.map((t) => {
                const isCredit = t.direction.toLowerCase() === 'credit'
                const formatted = formatMinorUnits(t.amount_base_minor, currency, locale)

                return (
                  <tr key={t.id} className="hover:bg-surface-muted/40 transition-colors">
                    <td className="py-3 px-4 whitespace-nowrap text-muted">
                      {new Date(t.occurred_at).toLocaleDateString(locale, {
                        month: 'short',
                        day: 'numeric',
                      })}
                    </td>
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-fg">{t.merchant_name}</span>
                        {t.is_anomaly && (
                          <span
                            title={t.anomaly_reason ?? 'Unusual pattern detected'}
                            className="inline-flex items-center gap-1 rounded bg-negative/10 text-negative px-1.5 py-0.5 text-[10px] font-semibold"
                          >
                            <AlertCircle className="h-3 w-3" aria-hidden="true" />
                            Anomaly
                          </span>
                        )}
                        {t.is_recurring && (
                          <span className="rounded bg-brand/10 text-brand px-1.5 py-0.5 text-[10px] font-medium">
                            Recurring
                          </span>
                        )}
                      </div>
                      {t.currency !== currency && (
                        <p className="text-[10px] text-muted">
                          Orig: {formatMinorUnits(t.amount_minor, t.currency)} @ FX{' '}
                          {Number(t.fx_rate.toPrecision(4))}
                        </p>
                      )}
                    </td>
                    <td className="py-3 px-4">
                      <select
                        aria-label={`Change category for ${t.merchant_name}`}
                        value={t.category}
                        onChange={(e) =>
                          void handleCategoryChange(t.id, e.target.value as CategoryOption)
                        }
                        className="rounded-lg border border-line-strong bg-bg px-2 py-1 text-xs text-fg focus:border-brand"
                      >
                        {ALL_CATEGORIES.map((cat) => (
                          <option key={cat} value={cat}>
                            {cat}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap">
                      <div className="flex items-center gap-1.5">
                        <span
                          className={[
                            'inline-block px-1.5 py-0.5 rounded text-[10px] uppercase font-semibold',
                            t.category_source === 'user'
                              ? 'bg-positive/10 text-positive'
                              : t.category_source === 'ml'
                                ? 'bg-brand/10 text-brand'
                                : 'bg-surface-muted text-muted',
                          ].join(' ')}
                        >
                          {t.category_source}
                        </span>
                        <span className="text-[10px] text-muted">
                          {Math.round(t.category_confidence * 100)}%
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 text-right whitespace-nowrap">
                      <span
                        className={[
                          'inline-flex items-center gap-1 font-semibold tabular',
                          isCredit ? 'text-positive' : 'text-fg',
                        ].join(' ')}
                      >
                        {isCredit ? (
                          <ArrowDownLeft className="h-3.5 w-3.5" aria-hidden="true" />
                        ) : (
                          <ArrowUpRight className="h-3.5 w-3.5 text-muted" aria-hidden="true" />
                        )}
                        {isCredit ? `+${formatted}` : formatted}
                      </span>
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
