import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api, ApiError } from '../lib/api'
import { clearSession, readSession, saveSession, UNAUTHORIZED_EVENT } from '../lib/session'
import type { User } from '../types'

interface AuthState {
  user: User | null
  loading: boolean
  notice: string
  login: (username: string, password: string) => Promise<User>
  logout: (message?: string) => void
  refreshUser: () => Promise<User>
}
const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState('')

  const logout = useCallback((message = '你已退出登录。') => {
    clearSession()
    setUser(null)
    setNotice(message)
  }, [])

  useEffect(() => {
    const expired = () => logout('登录已过期或账号状态已变更，请重新登录。')
    window.addEventListener(UNAUTHORIZED_EVENT, expired)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, expired)
  }, [logout])

  useEffect(() => {
    if (!readSession()) { setLoading(false); return }
    const controller = new AbortController()
    api.me(controller.signal).then(setUser).catch((error: unknown) => {
      if (!controller.signal.aborted) {
        if (!(error instanceof ApiError && error.status === 401)) {
          setNotice('暂时无法验证登录状态，请确认服务可用后重新登录。')
        }
      }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [])

  const refreshUser = useCallback(async () => {
    const current = await api.me()
    setUser(current)
    return current
  }, [])

  useEffect(() => {
    if (!user) return
    const session = readSession()
    if (!session) { logout('登录已过期，请重新登录。'); return }
    const timeout = window.setTimeout(() => logout('登录已过期，请重新登录。'), session.expiresAt - Date.now())
    const refresh = () => { if (document.visibilityState === 'visible') void refreshUser().catch(() => {}) }
    const interval = window.setInterval(refresh, 60_000)
    window.addEventListener('focus', refresh)
    return () => { clearTimeout(timeout); clearInterval(interval); window.removeEventListener('focus', refresh) }
  }, [user, logout, refreshUser])

  const login = useCallback(async (username: string, password: string) => {
    const result = await api.login(username.trim(), password)
    saveSession(result.accessToken, result.expiresIn)
    setNotice('')
    setUser(result.user)
    return result.user
  }, [])

  return <AuthContext.Provider value={{ user, loading, notice, login, logout, refreshUser }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('AuthProvider is missing')
  return context
}
