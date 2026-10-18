import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, readSession, saveSession, SESSION_KEY } from './session'

describe('tab-scoped sessions', () => {
  beforeEach(() => { clearSession(); vi.useRealTimers() })

  it('stores only the token and expiry, without a password or user profile', () => {
    saveSession('test-token', 3600)
    expect(readSession()?.token).toBe('test-token')
    expect(Object.keys(JSON.parse(sessionStorage.getItem(SESSION_KEY)!))).toEqual(['token', 'expiresAt'])
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('clears expired sessions', () => {
    vi.useFakeTimers()
    saveSession('test-token', 1)
    vi.advanceTimersByTime(1001)
    expect(readSession()).toBeNull()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    vi.useRealTimers()
  })

  it('discards malformed session data', () => {
    sessionStorage.setItem(SESSION_KEY, '{broken')
    expect(readSession()).toBeNull()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('rejects a session with invalid field types', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 123, expiresAt: Date.now() + 1000 }))
    expect(readSession()).toBeNull()
  })
})
