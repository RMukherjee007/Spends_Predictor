import { useState, type FormEvent } from 'react'
import { AlertCircle, ShieldCheck, TrendingUp } from 'lucide-react'
import { useAuth } from '@/context/AuthContext'

export function LoginPage() {
  const { devLogin } = useAuth()
  const [email, setEmail] = useState('asha@example.com')
  const [password, setPassword] = useState('password123')
  const [fullName, setFullName] = useState('Asha Rao')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await devLogin(email, fullName, password)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-dvh bg-bg text-fg flex flex-col justify-center px-4 py-8 sm:px-6">
      <div className="mx-auto w-full max-w-md space-y-6">
        <div className="rounded-2xl border border-line bg-surface p-6 sm:p-8 space-y-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand text-brand-fg">
              <TrendingUp className="h-5 w-5" aria-hidden="true" />
            </div>
            <div>
              <h1 className="text-lg font-semibold tracking-tight">Sign in to Spends Predictor</h1>
              <p className="text-xs text-muted">
                Forecast your spending before the month runs out.
              </p>
            </div>
          </div>

          {error && (
            <div
              role="alert"
              className="flex items-start gap-2.5 rounded-xl border border-negative px-3.5 py-2.5 text-sm text-negative"
            >
              <AlertCircle className="h-4 w-4 shrink-0 mt-0.5" aria-hidden="true" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="email" className="block text-xs font-medium text-fg">
                Email address
              </label>
              <input
                id="email"
                name="email"
                type="email"
                inputMode="email"
                autoComplete="username"
                spellCheck={false}
                required
                enterKeyHint="next"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg placeholder:text-muted"
                placeholder="you@example.com"
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="block text-xs font-medium text-fg">
                Password
              </label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                minLength={6}
                enterKeyHint="next"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg placeholder:text-muted"
                placeholder="Minimum 6 characters"
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="full-name" className="block text-xs font-medium text-fg">
                Full name <span className="text-muted font-normal">(optional, used on first sign-in)</span>
              </label>
              <input
                id="full-name"
                name="name"
                type="text"
                autoComplete="name"
                enterKeyHint="done"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                className="w-full rounded-xl border border-line-strong bg-bg px-3.5 py-2.5 text-sm text-fg placeholder:text-muted"
                placeholder="Asha Rao"
              />
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-xl bg-brand px-4 py-2.5 text-sm font-semibold text-brand-fg transition-opacity hover:opacity-95 disabled:opacity-50 cursor-pointer"
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
        </div>

        <div className="rounded-2xl border border-line bg-surface px-5 py-4 flex items-center gap-3 text-xs text-muted">
          <ShieldCheck className="h-4 w-4 shrink-0 text-positive" aria-hidden="true" />
          <span>
            AES-256-GCM encrypted at rest · Multi-currency (ISO-4217) · SHA-256 ledger verification
          </span>
        </div>
      </div>
    </div>
  )
}
