export const ROLE_CODES = ['ADMINISTRATOR', 'DEVELOPER', 'APPROVER'] as const
export type Role = (typeof ROLE_CODES)[number]

export const ROLE_LABELS: Record<Role, string> = {
  ADMINISTRATOR: '管理员', DEVELOPER: '开发者', APPROVER: '审批人',
}
export const ROLE_DESCRIPTIONS: Record<Role, string> = {
  ADMINISTRATOR: '管理用户、分配角色和维护软件服务',
  DEVELOPER: '负责事件调查与变更实施',
  APPROVER: '负责变更请求的审核与批准',
}

export interface User {
  id: number
  username: string
  email: string
  displayName: string
  enabled: boolean
  roles: Role[]
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
}

export interface UserPage {
  items: User[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export interface CreateUserRequest {
  username: string
  email: string
  displayName: string
  password: string
  roles: Role[]
}

export interface ApiProblem {
  status: number
  code: string
  message: string
  path?: string
  fields?: Record<string, string>
}

export interface ServiceOwner { id: number; username: string; displayName: string; enabled: boolean }
export interface SoftwareService {
  id: number
  code: string
  name: string
  description: string | null
  ownerId: number | null
  owner: ServiceOwner | null
  enabled: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
  version: number
}
export interface ServicePage {
  items: SoftwareService[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export interface SaveServiceRequest { code: string; name: string; description: string | null; ownerId: number | null }

export const INCIDENT_STATUSES = ['OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'CLOSED'] as const
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number]
export const INCIDENT_SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
export type IncidentSeverity = (typeof INCIDENT_SEVERITIES)[number]
export interface PageResult<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface Incident {
  id: number
  service: { id: number; code: string; name: string; enabled: boolean }
  reporter: ServiceOwner
  assignee: ServiceOwner | null
  title: string
  description: string
  severity: IncidentSeverity
  status: IncidentStatus
  resolution: string | null
  sourceChangeId: number | null
  resolvedAt: string | null
  closedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
  permissions: { canAssign: boolean; canInvestigate: boolean; canResolve: boolean; canClose: boolean; canAddNote: boolean }
}
export interface CreateIncidentRequest { serviceId: number; title: string; description: string; severity: IncidentSeverity }
export interface IncidentNote { id: number; incidentId: number; author: ServiceOwner; content: string; createdAt: string }
export interface IncidentHistory { id: number; incidentId: number; oldStatus: IncidentStatus | null; newStatus: IncidentStatus; changedBy: ServiceOwner; comment: string | null; changedAt: string }

export const CHANGE_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'IMPLEMENTING', 'COMPLETED', 'FAILED'] as const
export type ChangeStatus = (typeof CHANGE_STATUSES)[number]
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export interface DraftChangeRequest {
  serviceId: number
  relatedIncidentId: number | null
  title: string
  businessReason: string | null
  riskLevel: RiskLevel | null
  plannedImplementationAt: string | null
  implementationPlan: string | null
  rollbackPlan: string | null
  version?: number
}
export interface ChangeRequest extends Omit<DraftChangeRequest, 'serviceId' | 'version'> {
  id: number
  service: Incident['service']
  requester: ServiceOwner
  status: ChangeStatus
  submissionNumber: number
  submittedAt: string | null
  implementationStartedAt: string | null
  implementationFinishedAt: string | null
  implementationResult: string | null
  failureIncidentId: number | null
  createdAt: string
  updatedAt: string
  version: number
  permissions: { canEdit: boolean; canSubmit: boolean; canReview: boolean; canStart: boolean; canFinish: boolean }
}
export interface ChangeReview { id: number; submissionNumber: number; reviewer: ServiceOwner; decision: 'APPROVED' | 'REJECTED'; comment: string | null; reviewedAt: string }
export interface ChangeHistory { id: number; oldStatus: ChangeStatus | null; newStatus: ChangeStatus; changedBy: ServiceOwner; comment: string | null; changedAt: string }
export interface DashboardRecent { id: number; title: string; serviceCode: string; status: string; level: string | null; createdAt: string }
export interface DashboardSummary {
  generatedAt: string; zoneId: string; totalServices: number; enabledServices: number; totalIncidents: number; activeIncidents: number
  totalChanges: number; pendingApprovals: number; implementingChanges: number; completedChanges: number; failedChanges: number; changeSuccessRate: number | null
  incidentsByStatus: Record<IncidentStatus, number>; incidentsBySeverity: Record<IncidentSeverity, number>
  changesByStatus: Record<ChangeStatus, number>; changesByRisk: Record<RiskLevel | 'UNASSESSED', number>
  trend: { date: string; incidents: number; changes: number }[]; recentIncidents: DashboardRecent[]; recentChanges: DashboardRecent[]
}

export const AUDIT_ENTITY_TYPES = ['USER', 'ROLE', 'SERVICE', 'INCIDENT', 'CHANGE_REQUEST'] as const
export type AuditEntityType = (typeof AUDIT_ENTITY_TYPES)[number]
export interface AuditLogFilters { actorId?: number; action?: string; entityType?: AuditEntityType; entityId?: number; from?: string; to?: string }
export interface AuditLogSummary {
  id: number
  actor: { id: number; username: string; displayName: string } | null
  action: string
  entityType: AuditEntityType
  entityId: number
  occurredAt: string
}
export interface AuditLogDetail extends AuditLogSummary { details: Record<string, unknown> }
