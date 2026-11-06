import { NavLink, Outlet } from 'react-router'
import {
  BrainCircuit,
  CreditCard,
  History,
  LayoutDashboard,
  LogOut,
  Settings,
  TrendingUp,
} from 'lucide-react'
import { useAuth } from '@/context/AuthContext'

const NAV_ITEMS = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/transactions', label: 'Transactions', icon: History, end: false },
  { to: '/intelligence', label: 'ML Intelligence', icon: BrainCircuit, end: false },
  { to: '/accounts', label: 'Accounts', icon: CreditCard, end: false },
  { to: '/settings', label: 'Settings', icon: Settings, end: false },
] as const

export function AppShell() {
  const { user, logout } = useAuth()

  return (
    <div className="min-h-dvh bg-bg text-fg flex flex-col md:flex-row">
      {/* Desktop sidebar */}
      <aside
        aria-label="Primary navigation"
        className="hidden md:flex md:w-64 md:flex-col md:justify-between border-r border-line bg-surface px-5 py-6"
      >
        <div className="space-y-8">
          <div className="flex items-center gap-3 px-2">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand text-brand-fg">
              <TrendingUp className="h-5 w-5" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-semibold tracking-tight">Spends Predictor</p>
              <p className="text-xs text-muted">Smart cashflow</p>
            </div>
          </div>

          <nav className="space-y-1">
            {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  [
                    'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-surface-muted text-fg'
                      : 'text-muted hover:bg-surface-muted/60 hover:text-fg',
                  ].join(' ')
                }
              >
                <Icon className="h-4 w-4" aria-hidden="true" />
                <span>{label}</span>
              </NavLink>
            ))}
          </nav>
        </div>

        <div className="border-t border-line pt-4 space-y-3">
          {user && (
            <div className="px-2">
              <p className="truncate text-sm font-medium">{user.full_name ?? user.email}</p>
              <p className="truncate text-xs text-muted">
                {user.email} · {user.base_currency}
              </p>
            </div>
          )}
          <button
            type="button"
            onClick={() => void logout()}
            className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium text-muted hover:bg-surface-muted hover:text-fg transition-colors cursor-pointer"
          >
            <LogOut className="h-4 w-4" aria-hidden="true" />
            <span>Sign out</span>
          </button>
        </div>
      </aside>

      {/* Main content */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Mobile top header */}
        <header className="flex md:hidden items-center justify-between border-b border-line bg-surface px-4 py-3">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-brand text-brand-fg">
              <TrendingUp className="h-4 w-4" aria-hidden="true" />
            </div>
            <span className="text-sm font-semibold tracking-tight">Spends Predictor</span>
          </div>
          <button
            type="button"
            onClick={() => void logout()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-line-strong px-2.5 py-1.5 text-xs font-medium text-fg"
          >
            <LogOut className="h-3.5 w-3.5" aria-hidden="true" />
            <span>Sign out</span>
          </button>
        </header>

        <main className="flex-1 mx-auto w-full max-w-5xl px-4 py-6 sm:px-6 md:py-10 pb-24 md:pb-10">
          <Outlet />
        </main>

        {/* Mobile bottom nav */}
        <nav
          aria-label="Mobile navigation"
          className="fixed bottom-0 inset-x-0 md:hidden border-t border-line bg-surface/95 backdrop-blur px-4 py-2 flex justify-around"
        >
          {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                [
                  'flex flex-col items-center gap-1 rounded-lg px-4 py-1 text-xs font-medium',
                  isActive ? 'text-brand' : 'text-muted',
                ].join(' ')
              }
            >
              <Icon className="h-4 w-4" aria-hidden="true" />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  )
}
