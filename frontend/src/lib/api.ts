import type { ApiProblem, CreateUserRequest, LoginResponse, Role, User, UserPage, SaveServiceRequest, ServicePage, SoftwareService } from '../types'
import { readSession, UNAUTHORIZED_EVENT } from './session'
import type { CreateIncidentRequest, Incident, IncidentHistory, IncidentNote, IncidentSeverity, IncidentStatus, PageResult, ServiceOwner } from '../types'
import type { ChangeHistory, ChangeRequest, ChangeReview, ChangeStatus, DashboardSummary, DraftChangeRequest, RiskLevel } from '../types'
import type { AuditLogDetail, AuditLogFilters, AuditLogSummary } from '../types'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string,
              public fields: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions { method?: string; body?: unknown; auth?: boolean; signal?: AbortSignal; root?: boolean }

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = options.auth === false ? undefined : readSession()?.token
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const timeout = AbortSignal.timeout(15_000)
  const signal = options.signal ? AbortSignal.any([timeout, options.signal]) : timeout
  let response: Response
  try {
    response = await fetch(options.root ? path : `/api/v1${path}`, {
      method: options.method || 'GET', headers, signal,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new ApiError(0, 'CONNECTION_ERROR', '暂时无法连接服务，请确认后端已启动后重试。')
  }
  let body: unknown = null
  try { body = await response.json() } catch { /* A proxy or gateway may return a non-JSON response. */ }
  if (!response.ok) {
    if (response.status === 401 && token && readSession()?.token === token) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    const problem = body as Partial<ApiProblem> | null
    const fallback = response.status === 403 ? '当前账号没有权限执行此操作。'
      : response.status >= 500 ? '服务暂时不可用，请稍后重试。' : '请求未能完成，请重试。'
    throw new ApiError(response.status, problem?.code || 'REQUEST_FAILED',
      typeof problem?.message === 'string' ? problem.message : fallback, problem?.fields || {})
  }
  return body as T
}

export const api = {
  login: (username: string, password: string) => request<LoginResponse>('/auth/login', {
    method: 'POST', body: { username, password }, auth: false,
  }),
  me: (signal?: AbortSignal) => request<User>('/auth/me', { signal }),
  users: (page = 0, size = 10, signal?: AbortSignal) => request<UserPage>(`/users?page=${page}&size=${size}`, { signal }),
  createUser: (body: CreateUserRequest) => request<User>('/users', { method: 'POST', body }),
  updateRoles: (id: number, roles: Role[]) => request<User>(`/users/${id}/roles`, { method: 'PUT', body: { roles } }),
  updateStatus: (id: number, enabled: boolean) => request<User>(`/users/${id}/status`, { method: 'PATCH', body: { enabled } }),
  services: (page = 0, size = 10, enabled?: boolean, signal?: AbortSignal) => request<ServicePage>(`/services?page=${page}&size=${size}${enabled === undefined ? '' : `&enabled=${enabled}`}`, { signal }),
  service: (id: number, signal?: AbortSignal) => request<SoftwareService>(`/services/${id}`, { signal }),
  createService: (body: SaveServiceRequest) => request<SoftwareService>('/services', { method: 'POST', body }),
  updateService: (id: number, body: SaveServiceRequest) => request<SoftwareService>(`/services/${id}`, { method: 'PUT', body }),
  updateServiceStatus: (id: number, enabled: boolean) => request<SoftwareService>(`/services/${id}/status`, { method: 'PATCH', body: { enabled } }),
  incidents: (page = 0, size = 10, filters: { status?: IncidentStatus; severity?: IncidentSeverity; serviceId?: number; assigneeId?: number } = {}, signal?: AbortSignal) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    for (const [key, value] of Object.entries(filters)) if (value !== undefined) query.set(key, String(value))
    return request<PageResult<Incident>>(`/incidents?${query}`, { signal })
  },
  incident: (id: number, signal?: AbortSignal) => request<Incident>(`/incidents/${id}`, { signal }),
  createIncident: (body: CreateIncidentRequest) => request<Incident>('/incidents', { method: 'POST', body }),
  incidentAssignees: (page = 0, size = 10, signal?: AbortSignal) => request<PageResult<ServiceOwner>>(`/incidents/assignees?page=${page}&size=${size}`, { signal }),
  assignIncident: (id: number, assigneeId: number, version: number, comment?: string) => request<Incident>(`/incidents/${id}/assignment`, { method: 'POST', body: { assigneeId, version, comment } }),
  transitionIncident: (id: number, status: IncidentStatus, version: number, resolution?: string, comment?: string) => request<Incident>(`/incidents/${id}/transitions`, { method: 'POST', body: { status, version, resolution, comment } }),
  incidentNotes: (id: number, page = 0, size = 10, signal?: AbortSignal) => request<PageResult<IncidentNote>>(`/incidents/${id}/notes?page=${page}&size=${size}`, { signal }),
  incidentHistory: (id: number, page = 0, size = 10, signal?: AbortSignal) => request<PageResult<IncidentHistory>>(`/incidents/${id}/history?page=${page}&size=${size}`, { signal }),
  addIncidentNote: (id: number, content: string, version: number) => request<IncidentNote>(`/incidents/${id}/notes`, { method: 'POST', body: { content, version } }),
  changes: (page = 0, size = 10, filters: { status?: ChangeStatus; riskLevel?: RiskLevel; serviceId?: number; requesterId?: number } = {}, signal?: AbortSignal) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    for (const [key, value] of Object.entries(filters)) if (value !== undefined) query.set(key, String(value))
    return request<PageResult<ChangeRequest>>(`/changes?${query}`, { signal })
  },
  change: (id: number, signal?: AbortSignal) => request<ChangeRequest>(`/changes/${id}`, { signal }),
  createChange: (body: DraftChangeRequest) => request<ChangeRequest>('/changes', { method: 'POST', body }),
  updateChange: (id: number, body: DraftChangeRequest) => request<ChangeRequest>(`/changes/${id}`, { method: 'PUT', body }),
  submitChange: (id: number, version: number) => request<ChangeRequest>(`/changes/${id}/submit`, { method: 'POST', body: { version } }),
  reviewChange: (id: number, version: number, decision: 'APPROVED' | 'REJECTED', comment?: string) => request<ChangeRequest>(`/changes/${id}/reviews`, { method: 'POST', body: { version, decision, comment } }),
  startChange: (id: number, version: number) => request<ChangeRequest>(`/changes/${id}/implementation/start`, { method: 'POST', body: { version } }),
  finishChange: (id: number, version: number, outcome: 'COMPLETED' | 'FAILED', result: string) => request<ChangeRequest>(`/changes/${id}/implementation/result`, { method: 'POST', body: { version, outcome, result } }),
  changeReviews: (id: number, page = 0, size = 10, signal?: AbortSignal) => request<PageResult<ChangeReview>>(`/changes/${id}/reviews?page=${page}&size=${size}`, { signal }),
  changeHistory: (id: number, page = 0, size = 10, signal?: AbortSignal) => request<PageResult<ChangeHistory>>(`/changes/${id}/history?page=${page}&size=${size}`, { signal }),
  dashboard: (signal?: AbortSignal) => request<DashboardSummary>('/dashboard/summary', { signal }),
  auditLogs: (page = 0, size = 10, filters: AuditLogFilters = {}, signal?: AbortSignal) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    for (const [key, value] of Object.entries(filters)) if (value !== undefined) query.set(key, String(value))
    return request<PageResult<AuditLogSummary>>(`/audit-logs?${query}`, { signal })
  },
  auditLog: (id: number, signal?: AbortSignal) => request<AuditLogDetail>(`/audit-logs/${id}`, { signal }),
  health: (signal?: AbortSignal) => request<{ status: string }>('/actuator/health', { root: true, auth: false, signal }),
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '操作未能完成，请重试。'
}
