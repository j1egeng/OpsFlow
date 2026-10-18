import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useI18n } from '../i18n/I18nContext'
import type { IncidentSeverity, IncidentStatus } from '../types'

export const STATUS_LABELS: Record<IncidentStatus, string> = { OPEN: '待分配', ASSIGNED: '已分配', INVESTIGATING: '调查中', RESOLVED: '已解决', CLOSED: '已关闭' }
export const SEVERITY_LABELS: Record<IncidentSeverity, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '紧急' }
export function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  const { t } = useI18n()
  return <span className={`incident-badge incident-${status.toLowerCase()}`}>{t(STATUS_LABELS[status])}</span>
}
export function SeverityBadge({ severity }: { severity: IncidentSeverity }) {
  const { t } = useI18n()
  return <span className={`severity-badge severity-${severity.toLowerCase()}`}><i />{t(SEVERITY_LABELS[severity])}</span>
}
export function SmallPagination({ page, total, loading, onChange }: { page: number; total: number; loading: boolean; onChange: (page: number) => void }) {
  const { t } = useI18n()
  return <div className="pagination"><span>{t('第 {page} / {total} 页', { page: page + 1, total: Math.max(total, 1) })}</span>
    <button type="button" className="icon-button" aria-label={t('上一页')} disabled={loading || page === 0} onClick={() => onChange(page - 1)}><ChevronLeft size={16} /></button>
    <button type="button" className="icon-button" aria-label={t('下一页')} disabled={loading || page + 1 >= total} onClick={() => onChange(page + 1)}><ChevronRight size={16} /></button></div>
}
