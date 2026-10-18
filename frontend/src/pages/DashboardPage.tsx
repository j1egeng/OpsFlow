import { Activity, ArrowRight, ArrowRightLeft, Boxes, CheckCheck, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChangeStatusBadge, CHANGE_LABELS } from '../components/ChangeUi'
import { IncidentStatusBadge, STATUS_LABELS } from '../components/IncidentUi'
import { Alert, formatDate, Spinner } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { CHANGE_STATUSES, INCIDENT_STATUSES } from '../types'
import type { ChangeStatus, DashboardRecent, DashboardSummary, IncidentStatus } from '../types'

export function DashboardPage() {
  const { t, locale } = useI18n()
  const [data, setData] = useState<DashboardSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.dashboard(controller.signal).then(result => { if (!controller.signal.aborted) setData(result) })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [revision])
  const refresh = () => setRevision(value => value + 1)
  function recent(items: DashboardRecent[], kind: 'incidents' | 'changes') {
    return <section className="surface dashboard-panel"><div className="dashboard-section-heading"><h2>{t(kind === 'incidents' ? '最近事件' : '最近变更')}</h2><Link className="text-link" to={`/${kind}`}>{t('查看全部')}<ArrowRight size={14} /></Link></div>
      {!items.length ? <p className="section-description">{t('暂无记录。')}</p> : <ul className="dashboard-recent">{items.map(item => <li key={item.id}><Link to={`/${kind}/${item.id}`}><span>{kind === 'incidents' ? 'INC' : 'CHG'}-{String(item.id).padStart(4, '0')}</span><strong>{item.title}</strong><small>{item.serviceCode}</small></Link>
        {kind === 'incidents' ? <IncidentStatusBadge status={item.status as IncidentStatus} /> : <ChangeStatusBadge status={item.status as ChangeStatus} />}</li>)}</ul>}</section>
  }
  const max = Math.max(1, ...(data?.trend.flatMap(day => [day.incidents, day.changes]) || []))
  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">OPERATIONS OVERVIEW</span><h1>{t('运营总览')}</h1><p>{t('实时汇总服务、事件和变更，掌握团队当前工作状态。')}</p></div>
    <button className="button button-secondary" onClick={refresh} disabled={loading}><RefreshCw size={16} className={loading ? 'spin' : ''} />{t('刷新统计')}</button></div>
    {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={refresh}>{t('重新加载')}</button></div>
      : loading ? <div className="surface table-loading"><Spinner label="正在加载统计" /></div> : data && <>
      <div className="dashboard-stats">
        <Link to="/services" className="stat-card" data-testid="metric-services"><div><span>{t('启用服务数')}</span><strong>{data.enabledServices}<small>{t('共 {count} 项', { count: data.totalServices })}</small></strong></div><span className="stat-icon stat-icon-green"><Boxes size={22} /></span></Link>
        <Link to="/incidents" className="stat-card" data-testid="metric-incidents"><div><span>{t('活跃事件')}</span><strong>{data.activeIncidents}<small>{t('共 {count} 项', { count: data.totalIncidents })}</small></strong></div><span className="stat-icon stat-icon-amber"><Activity size={22} /></span></Link>
        <Link to="/changes" className="stat-card" data-testid="metric-pending"><div><span>{t('待审批变更')}</span><strong>{data.pendingApprovals}<small>{t('实施中 {count} 项', { count: data.implementingChanges })}</small></strong></div><span className="stat-icon stat-icon-blue"><ArrowRightLeft size={22} /></span></Link>
        <div className="stat-card" data-testid="metric-success"><div><span>{t('变更成功率')}</span><strong>{data.changeSuccessRate === null ? '—' : `${data.changeSuccessRate}%`}<small>{t('已结束 {count} 项', { count: data.completedChanges + data.failedChanges })}</small></strong></div><span className="stat-icon stat-icon-green"><CheckCheck size={22} /></span></div>
      </div><section className="surface dashboard-panel"><div className="dashboard-section-heading"><div><h2>{t('近七天新增趋势')}</h2><p>{t('按 UTC 自然日统计，今日数据截至本次刷新。')}</p></div><div className="trend-legend"><span><i className="trend-incident" />{t('事件')}</span><span><i className="trend-change" />{t('变更')}</span></div></div>
        <div className="trend-chart" role="img" aria-label={t('最近七天每日新增事件和变更数量')}>
          {data.trend.map(day => <div className="trend-day" key={day.date}><div className="trend-bars">
            <div className="trend-column"><span>{day.incidents}</span><i className="trend-incident" style={{ height: `${day.incidents / max * 110}px` }} /></div>
            <div className="trend-column"><span>{day.changes}</span><i className="trend-change" style={{ height: `${day.changes / max * 110}px` }} /></div></div>
            <time dateTime={day.date}>{new Intl.DateTimeFormat(locale, { month: '2-digit', day: '2-digit', timeZone: 'UTC' }).format(new Date(`${day.date}T12:00:00Z`))}</time></div>)}
        </div>
        <details className="trend-data"><summary>{t('查看趋势数据')}</summary><div className="table-scroll"><table className="users-table" aria-label={t('趋势数据')}><thead><tr><th>{t('日期（UTC）')}</th><th>{t('事件')}</th><th>{t('变更')}</th></tr></thead><tbody>{data.trend.map(day => <tr key={day.date}><td>{day.date}</td><td>{day.incidents}</td><td>{day.changes}</td></tr>)}</tbody></table></div></details>
      </section><div className="dashboard-grid"><section className="surface dashboard-panel"><h2>{t('事件状态分布')}</h2><div className="distribution-list">{INCIDENT_STATUSES.map(status => <div key={status}><span>{t(STATUS_LABELS[status])}</span><div><i style={{ width: `${data.totalIncidents ? data.incidentsByStatus[status] / data.totalIncidents * 100 : 0}%` }} /></div><strong>{data.incidentsByStatus[status]}</strong></div>)}</div></section>
        <section className="surface dashboard-panel"><h2>{t('变更状态分布')}</h2><div className="distribution-list">{CHANGE_STATUSES.map(status => <div key={status}><span>{t(CHANGE_LABELS[status])}</span><div><i style={{ width: `${data.totalChanges ? data.changesByStatus[status] / data.totalChanges * 100 : 0}%` }} /></div><strong>{data.changesByStatus[status]}</strong></div>)}</div></section></div>
      <div className="dashboard-grid">{recent(data.recentIncidents, 'incidents')}{recent(data.recentChanges, 'changes')}</div>
      <p className="dashboard-footnote">{t('统计快照：{time}', { time: formatDate(data.generatedAt, true, locale) })} · {t('活跃事件不包含已解决和已关闭；成功率仅计算已结束的实施。')}</p>
    </>}
  </div>
}
