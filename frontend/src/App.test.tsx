import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { formatMinorUnits } from './lib/money'

describe('formatMinorUnits', () => {
  it('formats 2-decimal (INR, USD), 0-decimal (JPY), and 3-decimal (KWD) currencies', () => {
    expect(formatMinorUnits(125050, 'INR', 'en-IN')).toContain('1,250.50')
    expect(formatMinorUnits(5000, 'JPY', 'ja-JP')).toContain('5,000')
    expect(formatMinorUnits(12500, 'KWD', 'en-US')).toContain('12.500')
  })
})

describe('App authentication flow', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders accessible sign-in form when unauthenticated and signs in via devLogin', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.endsWith('/api/v1/users/me')) {
        return new Response(JSON.stringify({ error: { code: 'not_authenticated', message: 'No' } }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.endsWith('/api/v1/auth/refresh')) {
        return new Response(JSON.stringify({ error: { code: 'not_authenticated', message: 'No' } }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if ((url.endsWith('/api/v1/auth/login') || url.endsWith('/api/v1/auth/dev-login')) && init?.method === 'POST') {
        return new Response(
          JSON.stringify({
            user: {
              id: '11111111-1111-1111-1111-111111111111',
              email: 'asha@example.com',
              full_name: 'Asha Rao',
              avatar_url: null,
              base_currency: 'INR',
              timezone: 'Asia/Kolkata',
              locale: 'en-IN',
              is_admin: false,
              created_at: '2026-10-27T00:00:00Z',
            },
            tokens: null,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      return new Response('{}', { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    const emailInput = await screen.findByLabelText(/email address/i)
    expect(emailInput).toHaveAttribute('type', 'email')
    expect(emailInput).toHaveAttribute('autocomplete', 'username')
    expect(emailInput).toBeRequired()

    const signInButton = screen.getByRole('button', { name: /sign in/i })
    await userEvent.click(signInButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /cashflow & spend forecast/i })).toBeInTheDocument()
    })
  })
})
