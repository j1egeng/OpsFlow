import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../lib/api'
import { useI18n } from '../i18n/I18nContext'
import type { AuditLogDetail } from '../types'
import { Alert, Dialog, Spinner } from './ui'
import { AuditAction, AUDIT_ENTITY_LABELS, auditTime } from './AuditUi'

export function AuditLogDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const { t, locale } = useI18n()
  const [data, setData] = useState<AuditLogDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setData(null)
    api.auditLog(id, controller.signal).then(result => { if (!controller.signal.aborted) setData(result) })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [id, revision])
  const target = data?.entityType === 'SERVICE' ? `/services/${data.entityId}`
    : data?.entityType === 'INCIDENT' ? `/incidents/${data.entityId}` : data?.entityType === 'CHANGE_REQUEST' ? `/changes/${data.entityId}` : null
  return <Dialog title="审计日志详情" description="只读记录，不支持修改或删除。" onClose={onClose}>
    <div className="dialog-body audit-detail">
      {loading ? <Spinner label="正在加载审计详情" /> : error ? <><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></>
        : data && <><dl className="audit-metadata">
          <div><dt>{t('日志编号')}</dt><dd>#{data.id}</dd></div>
          <div><dt>{t('操作人')}</dt><dd>{data.actor ? <>{data.actor.displayName}<small>@{data.actor.username} · ID {data.actor.id}</small></> : t('系统操作')}</dd></div>
          <div className="audit-metadata-wide"><dt>{t('发生时间')}</dt><dd><time dateTime={data.occurredAt}>{auditTime(data.occurredAt, locale)}</time><small>{data.occurredAt}</small></dd></div>
          <div><dt>{t('动作')}</dt><dd><AuditAction action={data.action} /></dd></div>
          <div><dt>{t('操作对象')}</dt><dd>{t(AUDIT_ENTITY_LABELS[data.entityType] || data.entityType)} #{data.entityId}<small>{data.entityType}</small></dd></div>
        </dl><section className="audit-payload" aria-label={t('事件数据（原始 JSON）')}><h3>{t('事件数据（原始 JSON）')}</h3><p>{t('原始字段和值保留原文，不随界面语言翻译。')}</p><pre tabIndex={0}>{JSON.stringify(data.details, null, 2)}</pre></section></>}
    </div><div className="dialog-footer">{target && !loading && !error && <Link className="text-link audit-target-link" to={target} onClick={onClose}>{t('查看关联对象')}</Link>}<button type="button" className="button button-secondary" onClick={onClose}>{t('关闭')}</button></div>
  </Dialog>
}
