import { ArrowRightLeft, Eye, Plus, RefreshCw, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ChangeEditor } from '../components/ChangeDialogs'
import { ChangeStatusBadge, CHANGE_LABELS } from '../components/ChangeUi'
import { SeverityBadge, SEVERITY_LABELS, SmallPagination } from '../components/IncidentUi'
import { Alert, formatDate, Spinner } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { CHANGE_STATUSES } from '../types'
import type { ChangeRequest, ChangeStatus, PageResult, RiskLevel } from '../types'

export function ChangesPage() {
  const { t, locale } = useI18n()
  const { user } = useAuth()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const serviceId = Number(params.get('serviceId')) || undefined
  const canCreate = !!user?.roles.some(role => role === 'ADMINISTRATOR' || role === 'DEVELOPER')
  const [data, setData] = useState<PageResult<ChangeRequest> | null>(null)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [status, setStatus] = useState<ChangeStatus | ''>('')
  const [risk, setRisk] = useState<RiskLevel | ''>('')
  const [mine, setMine] = useState(false)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.changes(page, size, { status: status || undefined, riskLevel: risk || undefined, serviceId, requesterId: mine ? user?.id : undefined }, controller.signal)
      .then(result => { if (!controller.signal.aborted) { if (page > 0 && !result.items.length) setPage(Math.max(result.totalPages - 1, 0)); else setData(result) } })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, size, status, risk, serviceId, mine, user?.id, revision])
  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">CHANGE CONTROL</span><h1>{t('变更管理')}<span className="heading-count">{loading ? '—' : data?.totalElements ?? '—'}</span></h1><p>{t('从申请、审批到实施，安全跟踪每一次服务变更。')}</p></div>
    {canCreate && <button className="button button-primary" onClick={() => setCreating(true)}><Plus size={18} />{t('新建变更')}</button>}</div>
    <section className="surface"><div className="table-heading"><div><h2>{t('变更列表')}</h2><p>{t('最新申请优先显示，所有审批轮次均可追溯。')}</p></div><span className="subtle-label">CHANGE REGISTER</span></div>
      <div className="incident-toolbar"><label className="service-filter"><span>{t('变更状态')}</span><select aria-label={t('变更状态')} value={status} onChange={event => { setStatus(event.target.value as ChangeStatus | ''); setPage(0) }}>
        <option value="">{t('全部状态')}</option>{CHANGE_STATUSES.map(value => <option key={value} value={value}>{t(CHANGE_LABELS[value])}</option>)}</select></label>
        <label className="service-filter"><span>{t('风险等级')}</span><select aria-label={t('风险等级')} value={risk} onChange={event => { setRisk(event.target.value as RiskLevel | ''); setPage(0) }}>
          <option value="">{t('全部级别')}</option>{(['LOW', 'MEDIUM', 'HIGH'] as const).map(value => <option key={value} value={value}>{t(SEVERITY_LABELS[value])}</option>)}</select></label>
        <label className="mine-filter"><input type="checkbox" checked={mine} onChange={event => { setMine(event.target.checked); setPage(0) }} />{t('由我申请')}</label>
        <button className="button button-secondary button-refresh" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />{t('刷新列表')}</button></div>
      {serviceId && <div className="incident-service-filter"><span>{t('关联服务编号：{id}', { id: serviceId })}</span><button className="text-link" onClick={() => { setParams({}); setPage(0) }}>{t('清除服务筛选')}</button></div>}
      {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
        : loading ? <div className="table-loading"><Spinner label="正在加载变更" /></div>
        : <div className="table-scroll"><table className="users-table incidents-table" aria-label={t('变更列表')}><thead><tr><th>{t('变更')}</th><th>{t('关联服务')}</th><th>{t('风险等级')}</th><th>{t('状态')}</th><th>{t('申请人')}</th><th>{t('计划时间')}</th><th>{t('操作')}</th></tr></thead>
          <tbody>{data?.items.map(change => <tr key={change.id}><td><div className="incident-cell"><span>CHG-{String(change.id).padStart(4, '0')}</span><Link className="service-name" to={`/changes/${change.id}`}>{change.title}</Link></div></td>
            <td><Link className="service-code" to={`/services/${change.service.id}`}>{change.service.code}</Link></td><td>{change.riskLevel ? <SeverityBadge severity={change.riskLevel} /> : <span className="date-cell">{t('尚未评估')}</span>}</td>
            <td><ChangeStatusBadge status={change.status} /></td><td className="service-owner">{change.requester.displayName}</td><td className="date-cell">{change.plannedImplementationAt ? formatDate(change.plannedImplementationAt, true, locale) : '—'}</td>
            <td><Link className="row-role-button" to={`/changes/${change.id}`} aria-label={t('查看变更 {id}', { id: change.id })}><Eye size={15} />{t('详情')}</Link></td></tr>)}
            {!data?.items.length && <tr><td colSpan={7}><div className="empty-state"><span><ArrowRightLeft size={27} /></span><h3>{t('没有符合条件的变更')}</h3><p>{t('调整筛选条件，或新建一份变更草稿。')}</p></div></td></tr>}
          </tbody></table></div>}
      <div className="table-footer"><div className="page-size"><span>{t('每页')}</span><select aria-label={t('每页变更数')} value={size} disabled={loading} onChange={event => { setSize(Number(event.target.value)); setPage(0) }}><option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select>
        <span>{t('条，共 {count} 项变更', { count: data?.totalElements ?? 0 })}</span></div><SmallPagination page={page} total={data?.totalPages || 0} loading={loading || !!error} onChange={setPage} /></div>
    </section><div className="access-note"><ShieldCheck size={16} /><span>{t('申请人与审批人必须不同；实施失败将自动生成关联事件。')}</span></div>
    {canCreate && creating && <ChangeEditor onClose={() => setCreating(false)} onSaved={change => navigate(`/changes/${change.id}`, { state: { notice: '变更草稿已创建。' } })} />}
  </div>
}
