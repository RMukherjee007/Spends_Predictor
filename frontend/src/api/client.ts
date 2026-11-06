import createClient, { type Middleware } from 'openapi-fetch'
import type { components, paths } from './schema'

export type UserRead = components['schemas']['UserRead']
export type UserUpdate = components['schemas']['UserUpdate']
export type CurrencyInfo = components['schemas']['CurrencyInfo']
export type AuthResponse = components['schemas']['AuthResponse']
export type BankAccountRead = components['schemas']['BankAccountRead']
export type TransactionRead = components['schemas']['TransactionRead']
export type MLInsightsResponse = components['schemas']['MLInsightsResponse']
export type CsvImportResponse = components['schemas']['CsvImportResponse']

export function extractApiError(err: unknown, fallback: string): string {
  if (
    err &&
    typeof err === 'object' &&
    'error' in err &&
    err.error &&
    typeof err.error === 'object' &&
    'message' in err.error &&
    typeof err.error.message === 'string'
  ) {
    return err.error.message
  }
  return fallback
}

const CSRF_COOKIE = 'sp_csrf'
const CSRF_HEADER = 'X-CSRF-Token'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const prefix = `${encodeURIComponent(name)}=`
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim()
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length))
    }
  }
  return null
}

const csrfMiddleware: Middleware = {
  async onRequest({ request }) {
    if (!SAFE_METHODS.has(request.method.toUpperCase())) {
      const csrf = readCookie(CSRF_COOKIE)
      if (csrf) {
        request.headers.set(CSRF_HEADER, csrf)
      }
    }
    return request
  },
  async onResponse({ request, response }) {
    if (
      response.status === 401 &&
      !request.url.includes('/api/v1/auth/') &&
      !request.headers.has('X-Retry-After-Refresh')
    ) {
      const refreshed = await tryRefreshSession()
      if (refreshed) {
        const retryReq = new Request(request, { headers: new Headers(request.headers) })
        retryReq.headers.set('X-Retry-After-Refresh', '1')
        const csrf = readCookie(CSRF_COOKIE)
        if (csrf && !SAFE_METHODS.has(retryReq.method.toUpperCase())) {
          retryReq.headers.set(CSRF_HEADER, csrf)
        }
        return globalThis.fetch(retryReq)
      }
    }
    return response
  },
}

const baseUrl =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  (typeof window !== 'undefined' ? window.location.origin : 'http://localhost')

export const api = createClient<paths>({
  baseUrl,
  credentials: 'include',
  fetch: (input) => globalThis.fetch(input),
})

api.use(csrfMiddleware)

let refreshPromise: Promise<boolean> | null = null

export async function tryRefreshSession(): Promise<boolean> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    const csrf = readCookie(CSRF_COOKIE)
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (csrf) headers[CSRF_HEADER] = csrf
    const resp = await fetch(`${baseUrl}/api/v1/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      headers,
      body: JSON.stringify({}),
    })
    return resp.ok
  })().finally(() => {
    refreshPromise = null
  })
  return refreshPromise
}
