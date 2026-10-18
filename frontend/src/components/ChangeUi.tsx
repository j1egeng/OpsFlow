import { useI18n } from '../i18n/I18nContext'
import type { ChangeStatus } from '../types'
export const CHANGE_LABELS: Record<ChangeStatus, string> = {
  DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', IMPLEMENTING: '实施中', COMPLETED: '已完成', FAILED: '失败',
}
export function ChangeStatusBadge({ status }: { status: ChangeStatus }) {
  const { t } = useI18n()
  return <span className={`incident-badge change-${status.toLowerCase()}`}>{t(CHANGE_LABELS[status])}</span>
}
