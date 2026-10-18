import { AlertTriangle, Eye, Plus, RefreshCw, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { CreateIncidentDialog } from '../components/IncidentDialogs'
import { IncidentStatusBadge, SeverityBadge, SmallPagination, SEVERITY_LABELS, STATUS_LABELS } from '../components/IncidentUi'
import { Alert, formatDate, Spinner } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { INCIDENT_SEVERITIES, INCIDENT_STATUSES } from '../types'
import type { Incident, IncidentSeverity, IncidentStatus, PageResult } from '../types'

export function IncidentsPage() {
  const { t, locale } = useI18n()
  const { user } = useAuth()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const serviceId = Number(searchParams.get('serviceId')) || undefined
  const canCreate = !!user?.roles.some(role => role === 'ADMINISTRATOR' || role === 'DEVELOPER')
  const [data, setData] = useState<PageResult<Incident> | null>(null)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [status, setStatus] = useState<IncidentStatus | ''>('')
  const [severity, setSeverity] = useState<IncidentSeverity | ''>('')
  const [mine, setMine] = useState(false)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.incidents(page, size, { status: status || undefined, severity: severity || undefined, serviceId, assigneeId: mine ? user?.id : undefined }, controller.signal)
      .then(result => { if (page > 0 && !result.items.length) setPage(Math.max(result.totalPages - 1, 0)); else setData(result) })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, size, status, severity, serviceId, mine, user?.id, revision])

  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">INCIDENT OPERATIONS</span>
    <h1>{t('事件管理')}<span className="heading-count">{loading ? '—' : data?.totalElements ?? '—'}</span></h1><p>{t('报告、调查和跟踪服务故障，保留完整处理记录。')}</p></div>
    {canCreate && <button className="button button-primary" onClick={() => setCreating(true)}><Plus size={18} />{t('报告事件')}</button>}</div>
    <section className="surface"><div className="table-heading"><div><h2>{t('事件列表')}</h2><p>{t('最新事件优先显示，筛选作用于全部记录。')}</p></div><span className="subtle-label">INCIDENT REGISTER</span></div>
      <div className="incident-toolbar"><label className="service-filter"><span>{t('事件状态')}</span><select aria-label={t('事件状态')} value={status} onChange={event => { setStatus(event.target.value as IncidentStatus | ''); setPage(0) }}>
        <option value="">{t('全部状态')}</option>{INCIDENT_STATUSES.map(value => <option key={value} value={value}>{t(STATUS_LABELS[value])}</option>)}</select></label>
        <label className="service-filter"><span>{t('严重程度')}</span><select aria-label={t('严重程度')} value={severity} onChange={event => { setSeverity(event.target.value as IncidentSeverity | ''); setPage(0) }}>
          <option value="">{t('全部级别')}</option>{INCIDENT_SEVERITIES.map(value => <option key={value} value={value}>{t(SEVERITY_LABELS[value])}</option>)}</select></label>
        <label className="mine-filter"><input type="checkbox" checked={mine} onChange={event => { setMine(event.target.checked); setPage(0) }} />{t('分配给我')}</label>
        <button className="button button-secondary button-refresh" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />{t('刷新列表')}</button></div>
      {serviceId && <div className="incident-service-filter"><span>{t('关联服务编号：{id}', { id: serviceId })}</span><button className="text-link" onClick={() => { setSearchParams({}); setPage(0) }}>{t('清除服务筛选')}</button></div>}
      {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
        : loading ? <div className="table-loading"><Spinner label="正在加载事件" /></div>
        : <div className="table-scroll"><table className="users-table incidents-table" aria-label={t('事件列表')}><thead><tr><th>{t('事件')}</th><th>{t('关联服务')}</th><th>{t('严重程度')}</th><th>{t('状态')}</th><th>{t('处理人')}</th><th>{t('创建时间')}</th><th>{t('操作')}</th></tr></thead>
          <tbody>{data?.items.map(incident => <tr key={incident.id}><td><div className="incident-cell"><span>INC-{String(incident.id).padStart(4, '0')}</span><Link className="service-name" to={`/incidents/${incident.id}`}>{incident.title}</Link></div></td>
            <td><Link className="service-code" to={`/services/${incident.service.id}`}>{incident.service.code}</Link></td><td><SeverityBadge severity={incident.severity} /></td><td><IncidentStatusBadge status={incident.status} /></td>
            <td><span className="service-owner">{incident.assignee?.displayName || t('未分配')}</span></td><td className="date-cell">{formatDate(incident.createdAt, false, locale)}</td>
            <td><Link className="row-role-button" to={`/incidents/${incident.id}`} aria-label={t('查看事件 {id}', { id: incident.id })}><Eye size={15} />{t('详情')}</Link></td></tr>)}
            {!data?.items.length && <tr><td colSpan={7}><div className="empty-state"><span><AlertTriangle size={27} /></span><h3>{t('没有符合条件的事件')}</h3><p>{t('调整筛选条件，或报告新的服务问题。')}</p></div></td></tr>}
          </tbody></table></div>}
      <div className="table-footer"><div className="page-size"><span>{t('每页')}</span><select value={size} aria-label={t('每页事件数')} disabled={loading} onChange={event => { setSize(Number(event.target.value)); setPage(0) }}>
        <option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select><span>{t('条，共 {count} 项事件', { count: data?.totalElements ?? 0 })}</span></div>
        <SmallPagination page={page} total={data?.totalPages || 0} loading={loading || !!error} onChange={setPage} /></div>
    </section><div className="access-note"><ShieldCheck size={16} /><span>{t('管理员分配和关闭事件，当前处理人负责调查、备注和解决。')}</span></div>
    {canCreate && creating && <CreateIncidentDialog onClose={() => setCreating(false)} onSaved={incident => navigate(`/incidents/${incident.id}`, { state: { notice: '事件已报告。' } })} />}
  </div>
}
