import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  api,
  extractApiError,
  type UserRead,
  type UserUpdate,
} from '@/api/client'

interface AuthContextValue {
  user: UserRead | null
  isLoading: boolean
  devLogin: (email: string, fullName?: string) => Promise<void>
  googleLogin: (idToken: string) => Promise<void>
  updateProfile: (patch: UserUpdate) => Promise<UserRead>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserRead | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let cancelled = false

    async function bootstrapSession() {
      try {
        const first = await api.GET('/api/v1/users/me')
        if (cancelled) return
        if (first.data) {
          setUser(first.data)
          setIsLoading(false)
          return
        }
      } catch {
        // Ignore network errors during bootstrap
      }
      if (!cancelled) {
        setUser(null)
        setIsLoading(false)
      }
    }

    void bootstrapSession()
    return () => {
      cancelled = true
    }
  }, [])

  const devLogin = useCallback(async (email: string, fullName?: string) => {
    const resp = await fetch('/api/v1/auth/dev-login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email,
        full_name: fullName?.trim() ? fullName.trim() : null,
        client: 'web',
      }),
    })
    if (!resp.ok) {
      throw new Error('Development sign-in failed. Please verify the backend server is running.')
    }
    const body = (await resp.json()) as { user: UserRead }
    setUser(body.user)
  }, [])

  const googleLogin = useCallback(async (idToken: string) => {
    const { data, error } = await api.POST('/api/v1/auth/google', {
      body: { id_token: idToken, client: 'web' },
    })
    if (error || !data) {
      throw new Error(extractApiError(error, 'Google sign-in failed'))
    }
    setUser(data.user)
  }, [])

  const updateProfile = useCallback(async (patch: UserUpdate) => {
    const { data, error } = await api.PATCH('/api/v1/users/me', { body: patch })
    if (error || !data) {
      throw new Error(extractApiError(error, 'Could not update profile'))
    }
    setUser(data)
    return data
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.POST('/api/v1/auth/logout', { body: {} })
    } catch {
      // Ignore logout network error in dev
    }
    setUser(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, isLoading, devLogin, googleLogin, updateProfile, logout }),
    [user, isLoading, devLogin, googleLogin, updateProfile, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
