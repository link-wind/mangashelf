import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api } from './api'

export interface Me {
  id: string
  login: string
  role: string
}

interface AuthState {
  me: Me | null
  loading: boolean
  refresh: () => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState>({ me: null, loading: true, refresh: async () => {}, logout: async () => {} })

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setMe(await api.get<Me>('/api/v1/auth/me'))
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    await api.post('/api/v1/auth/logout')
    setMe(null)
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  return <AuthContext.Provider value={{ me, loading, refresh, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
