import { useEffect, useState, type FormEvent } from 'react'
import { AlertCircle, CheckCircle2 } from 'lucide-react'
import { api, type CurrencyInfo } from '@/api/client'
import { useAuth } from '@/context/AuthContext'

const COMMON_TIMEZONES = [
  'Asia/Kolkata',
  'UTC',
  'America/New_York',
  'America/Los_Angeles',
  'Europe/London',
  'Europe/Berlin',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Australia/Sydney',
]

export function SettingsPage() {
  const { user, updateProfile } = useAuth()
  const [fullName, setFullName] = useState(user?.full_name ?? '')
  const [baseCurrency, setBaseCurrency] = useState(user?.base_currency ?? 'INR')
  const [timezone, setTimezone] = useState(user?.timezone ?? 'Asia/Kolkata')
  const [currencies, setCurrencies] = useState<CurrencyInfo[]>([])
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState<{ kind: 'success' | 'error'; message: string } | null>(null)

  useEffect(() => {
    let active = true
    void api.GET('/api/v1/meta/currencies').then(({ data }) => {
      if (active && data) setCurrencies(data)
    })
    return () => {
      active = false
    }
  }, [])

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    setStatus(null)
    try {
      await updateProfile({
        full_name: fullName.trim() || null,
        base_currency: baseCurrency,
        timezone,
      })
      setStatus({ kind: 'success', message: 'Preferences saved.' })
    } catch (err) {
      setStatus({
        kind: 'error',
        message: err instanceof Error ? err.message : 'Could not update preferences',
      })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="max-w-xl space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Account &amp; Regional Settings</h1>
        <p className="text-xs text-muted">
          Choose the base currency and timezone used for your spend forecasts and monthly budgets.
        </p>
      </div>

      <form
        onSubmit={(e) => void handleSave(e)}
        className="rounded-2xl border border-line bg-surface p-6 space-y-5"
      >
        {status && (
          <div
            role="status"
            className={[
              'flex items-center gap-2 rounded-xl border px-3.5 py-2.5 text-sm',
              status.kind === 'success'
                ? 'border-positive text-positive'
                : 'border-negative text-negative',
            ].join(' ')}
          >
            {status.kind === 'success' ? (
              <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />
            ) : (
              <AlertCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
            )}
            <span>{status.message}</span>
          </div>
        )}

        <div className="space-y-1.5">
          <label htmlFor="settings-name" className="block text-xs font-medium text-fg">
            Full name
          </label>
          <input
            id="settings-name"
            name="name"
            type="text"
            autoComplete="name"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg"
          />
        </div>

        <div className="space-y-1.5">
          <label htmlFor="settings-currency" className="block text-xs font-medium text-fg">
            Base currency (ISO-4217)
          </label>
          <select
            id="settings-currency"
            name="base_currency"
            value={baseCurrency}
            onChange={(e) => setBaseCurrency(e.target.value)}
            className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg"
          >
            {(currencies.length > 0
              ? currencies
              : [{ code: baseCurrency, minor_units: 2 }]
            ).map((c) => (
              <option key={c.code} value={c.code}>
                {c.code} ({c.minor_units} decimal digits)
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1.5">
          <label htmlFor="settings-timezone" className="block text-xs font-medium text-fg">
            Timezone
          </label>
          <select
            id="settings-timezone"
            name="timezone"
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
            className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg"
          >
            {(COMMON_TIMEZONES.includes(timezone)
              ? COMMON_TIMEZONES
              : [timezone, ...COMMON_TIMEZONES]
            ).map((tz) => (
              <option key={tz} value={tz}>
                {tz}
              </option>
            ))}
          </select>
        </div>

        <button
          type="submit"
          disabled={saving}
          className="rounded-xl bg-brand px-4 py-2.5 text-sm font-semibold text-brand-fg hover:opacity-95 disabled:opacity-50 cursor-pointer"
        >
          {saving ? 'Saving…' : 'Save preferences'}
        </button>
      </form>
    </div>
  )
}
