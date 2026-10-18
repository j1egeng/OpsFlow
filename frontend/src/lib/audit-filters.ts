import type { AuditEntityType, AuditLogFilters } from '../types'

export interface AuditFilterDraft { actorId: string; action: string; entityType: AuditEntityType | ''; entityId: string; from: string; to: string }
export const EMPTY_AUDIT_FILTERS: AuditFilterDraft = { actorId: '', action: '', entityType: '', entityId: '', from: '', to: '' }

function positiveId(value: string) {
  const text = value.trim()
  if (!text) return undefined
  const number = Number(text)
  if (!/^[1-9]\d*$/.test(text) || !Number.isSafeInteger(number)) throw new Error('操作人和对象编号必须是有效的正整数。')
  return number
}

function instant(value: string) {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('筛选时间格式无效。')
  return date.toISOString()
}

export function buildAuditFilters(draft: AuditFilterDraft): AuditLogFilters {
  const from = instant(draft.from)
  const to = instant(draft.to)
  if (from && to && Date.parse(from) >= Date.parse(to)) throw new Error('开始时间必须早于结束时间')
  const action = draft.action.trim()
  if (action.length > 80) throw new Error('动作代码最多 80 个字符。')
  return { actorId: positiveId(draft.actorId), action: action || undefined, entityType: draft.entityType || undefined,
    entityId: positiveId(draft.entityId), from, to }
}
