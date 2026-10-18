import { useI18n } from '../i18n/I18nContext'
import type { AuditEntityType } from '../types'
import type { Locale } from '../i18n/messages'

export const AUDIT_ENTITY_LABELS: Record<AuditEntityType, string> = { USER: '用户', ROLE: '角色', SERVICE: '服务', INCIDENT: '事件', CHANGE_REQUEST: '变更' }
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  USER_LOGIN: '用户登录', USER_CREATED: '用户已创建', USER_ROLES_UPDATED: '用户角色已更新', USER_STATUS_UPDATED: '用户状态已更新',
  SERVICE_CREATED: '服务已创建。', SERVICE_UPDATED: '服务资料已更新。', SERVICE_STATUS_UPDATED: '服务状态已更新',
  INCIDENT_CREATED: '事件已报告', INCIDENT_ASSIGNED: '事件已分派', INCIDENT_STATUS_CHANGED: '事件状态已更新', INCIDENT_NOTE_ADDED: '调查备注已追加',
  CHANGE_CREATED: '变更草稿已创建。', CHANGE_UPDATED: '变更已更新。', CHANGE_SUBMITTED: '变更已提交审批', CHANGE_REVIEWED: '变更已审批',
  CHANGE_STARTED: '变更实施已开始', CHANGE_FINISHED: '变更实施已结束',
}

export function AuditAction({ action }: { action: string }) {
  const { t } = useI18n()
  return <div className="audit-action">{AUDIT_ACTION_LABELS[action] && <span>{t(AUDIT_ACTION_LABELS[action])}</span>}<code>{action}</code></div>
}

export function auditTime(value: string, locale: Locale) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short' }).format(date)
}
