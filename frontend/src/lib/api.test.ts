import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'
import { clearSession, saveSession, UNAUTHORIZED_EVENT } from './session'

describe('API client', () => {
  beforeEach(() => { clearSession(); vi.unstubAllGlobals() })

  it('encodes audit filters and fetches read-only detail with bearer authentication', async () => {
    saveSession('audit-token', 3600)
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    await api.auditLogs(1, 20, { actorId: 2, action: 'A&B', entityType: 'SERVICE', entityId: 7,
      from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' })
    await api.auditLog(8)
    await api.auditLogs()
    const query = new URL(fetch.mock.calls[0][0], 'http://localhost').searchParams
    expect(Object.fromEntries(query)).toEqual({ page: '1', size: '20', actorId: '2', action: 'A&B', entityType: 'SERVICE', entityId: '7', from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' })
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/audit-logs/8')
    expect(fetch.mock.calls[2][0]).toBe('/api/v1/audit-logs?page=0&size=10')
    for (const call of fetch.mock.calls) {
      expect(call[1]).toMatchObject({ method: 'GET', headers: { Authorization: 'Bearer audit-token' } })
      expect(call[1].body).toBeUndefined()
    }
  })

  it('sends the bearer token on protected requests', async () => {
    saveSession('signed-token', 3600)
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 1 }), { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    await api.me()
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/me', expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer signed-token' }) }))
  })

  it('never attaches an existing token to login', async () => {
    saveSession('old-token', 3600)
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    await api.login('user', 'entered-password')
    expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('Authorization')
  })

  it('signals session expiration on protected 401 responses', async () => {
    saveSession('invalid-token', 3600)
    const expired = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"登录已过期"}', { status: 401 })))
    await expect(api.me()).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledOnce()
    window.removeEventListener(UNAUTHORIZED_EVENT, expired)
  })

  it('preserves field errors from the backend', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'VALIDATION_ERROR', message: '字段不合法', fields: { email: '邮箱格式错误' } }), { status: 400 })))
    await expect(api.login('user', 'password')).rejects.toMatchObject({ fields: { email: '邮箱格式错误' } })
  })

  it('shows a friendly message for non-JSON gateway errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 })))
    await expect(api.me()).rejects.toMatchObject({ status: 502, message: '服务暂时不可用，请稍后重试。' })
  })

  it('distinguishes connection failures from incorrect credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api.me()).rejects.toBeInstanceOf(ApiError)
    await expect(api.me()).rejects.toMatchObject({ code: 'CONNECTION_ERROR', status: 0 })
  })

  it('sends service pagination and optional enabled filters to the backend', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    await api.services(1, 20)
    await api.services(0, 10, false)
    await api.services(0, 10, true)
    expect(fetch.mock.calls.map(call => call[0])).toEqual([
      '/api/v1/services?page=1&size=20', '/api/v1/services?page=0&size=10&enabled=false', '/api/v1/services?page=0&size=10&enabled=true',
    ])
  })

  it('uses the agreed methods and bodies for all service writes and detail reads', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    const body = { code: 'payment', name: 'Payment', description: null, ownerId: null }
    await api.createService(body)
    await api.updateService(7, body)
    await api.updateServiceStatus(7, false)
    await api.service(7)
    expect(fetch.mock.calls.map(call => [call[0], call[1].method, call[1].body])).toEqual([
      ['/api/v1/services', 'POST', JSON.stringify(body)], ['/api/v1/services/7', 'PUT', JSON.stringify(body)],
      ['/api/v1/services/7/status', 'PATCH', '{"enabled":false}'], ['/api/v1/services/7', 'GET', undefined],
    ])
  })

  it('sends database incident filters and the version on incident writes', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    await api.incidents(1, 20, { status: 'INVESTIGATING', severity: 'HIGH', serviceId: 4, assigneeId: 2 })
    await api.assignIncident(7, 2, 0, 'Assign')
    await api.transitionIncident(7, 'RESOLVED', 2, 'Fixed', 'Checked')
    await api.addIncidentNote(7, 'Investigation note', 3)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/incidents?page=1&size=20&status=INVESTIGATING&severity=HIGH&serviceId=4&assigneeId=2')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ assigneeId: 2, version: 0, comment: 'Assign' })
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ status: 'RESOLVED', version: 2, resolution: 'Fixed', comment: 'Checked' })
    expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ content: 'Investigation note', version: 3 })
  })
  it('connects change workflow writes using the current record version', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    await api.submitChange(8, 1)
    await api.reviewChange(8, 2, 'REJECTED', 'Improve rollback')
    await api.startChange(8, 4)
    await api.finishChange(8, 5, 'FAILED', 'Rolled back')
    expect(fetch.mock.calls.map(call => [call[0], JSON.parse(call[1].body)])).toEqual([
      ['/api/v1/changes/8/submit', { version: 1 }], ['/api/v1/changes/8/reviews', { version: 2, decision: 'REJECTED', comment: 'Improve rollback' }],
      ['/api/v1/changes/8/implementation/start', { version: 4 }], ['/api/v1/changes/8/implementation/result', { version: 5, outcome: 'FAILED', result: 'Rolled back' }],
    ])
  })
  it('reads change filters, histories and global dashboard data from the backend', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}', { status: 200 })))
    vi.stubGlobal('fetch', fetch)
    await api.changes(1, 20, { status: 'PENDING_APPROVAL', riskLevel: 'HIGH', requesterId: 2 })
    await api.changeReviews(8, 1, 10)
    await api.changeHistory(8)
    await api.dashboard()
    expect(fetch.mock.calls.map(call => call[0])).toEqual([
      '/api/v1/changes?page=1&size=20&status=PENDING_APPROVAL&riskLevel=HIGH&requesterId=2',
      '/api/v1/changes/8/reviews?page=1&size=10', '/api/v1/changes/8/history?page=0&size=10', '/api/v1/dashboard/summary',
    ])
  })
})
