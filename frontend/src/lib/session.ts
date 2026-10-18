export const SESSION_KEY = 'opsflow.session.v1'
export const UNAUTHORIZED_EVENT = 'opsflow:unauthorized'

interface Session { token: string; expiresAt: number }
let memorySession: Session | null = null

export function clearSession() {
  memorySession = null
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* Storage may be unavailable. */ }
}

export function saveSession(token: string, expiresIn: number) {
  memorySession = { token, expiresAt: Date.now() + expiresIn * 1000 }
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(memorySession)) } catch { /* In-memory fallback. */ }
}

export function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (raw) {
      const value = JSON.parse(raw) as Partial<Session>
      if (typeof value.token !== 'string' || !value.token || typeof value.expiresAt !== 'number'
          || !Number.isFinite(value.expiresAt)) {
        clearSession()
        return null
      }
      memorySession = value as Session
    }
  } catch (error) {
    if (error instanceof SyntaxError) { clearSession(); return null }
    // Retain the in-memory session only if browser storage is unavailable.
  }
  if (memorySession && memorySession.expiresAt <= Date.now()) clearSession()
  return memorySession
}
