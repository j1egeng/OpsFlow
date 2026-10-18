import { Eye, FileSearch, LockKeyhole, RefreshCw, Search, ShieldCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { AuditLogDialog } from '../components/AuditLogDialog'
import { AuditAction, AUDIT_ACTION_LABELS, AUDIT_ENTITY_LABELS, auditTime } from '../components/AuditUi'
import { SmallPagination } from '../components/IncidentUi'
import { Alert, Spinner } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { buildAuditFilters, EMPTY_AUDIT_FILTERS } from '../lib/audit-filters'
import type { AuditFilterDraft } from '../lib/audit-filters'
import { AUDIT_ENTITY_TYPES } from '../types'
import type { AuditLogFilters, AuditLogSummary, PageResult } from '../types'

export function AuditLogsPage() {
  const { t, locale } = useI18n()
  const [form, setForm] = useState<AuditFilterDraft>({ ...EMPTY_AUDIT_FILTERS })
  const [filters, setFilters] = useState<AuditLogFilters>({})
  const [validation, setValidation] = useState('')
  const [data, setData] = useState<PageResult<AuditLogSummary> | null>(null)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<number | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.auditLogs(page, size, filters, controller.signal).then(result => {
      if (!controller.signal.aborted) {
        if (page > 0 && !result.items.length) setPage(Math.max(result.totalPages - 1, 0))
        else setData(result)
      }
    }).catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, size, filters, revision])

  function query(event: FormEvent) {
    event.preventDefault()
    try { const next = buildAuditFilters(form); setFilters(next); setPage(0); setValidation('') }
    catch (failure) { setValidation(errorMessage(failure)) }
  }
  function reset() { setForm({ ...EMPTY_AUDIT_FILTERS }); setFilters({}); setPage(0); setValidation('') }
  function field<Key extends keyof AuditFilterDraft>(key: Key, value: AuditFilterDraft[Key]) { setForm(current => ({ ...current, [key]: value })); setValidation('') }
  const count = loading || error ? '—' : data?.totalElements ?? 0
  return <div className="page-enter audit-page"><div className="page-heading"><div><span className="eyebrow">AUDIT TRAIL</span><h1>{t('审计日志')}<span className="heading-count">{count}</span></h1><p>{t('追溯团队操作，查看业务记录的变化与责任人。')}</p></div><span className="audit-readonly"><LockKeyhole size={15} />{t('只读审计')}</span></div>
    <section className="surface"><div className="table-heading"><div><h2>{t('审计记录')}</h2><p>{t('按发生时间倒序排列，筛选条件在全库执行。')}</p></div><button type="button" className="button button-secondary" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />{t('刷新列表')}</button></div>
      <form className="audit-filters" onSubmit={query} aria-label={t('审计筛选')}>
        <div className="audit-filter-grid"><label className="field"><span>{t('操作人 ID')}</span><input name="actorId" inputMode="numeric" maxLength={16} placeholder={t('不限')} value={form.actorId} onChange={event => field('actorId', event.target.value)} /></label>
          <label className="field"><span>{t('动作代码')}</span><input name="action" list="audit-actions" maxLength={80} placeholder="SERVICE_CREATED" value={form.action} onChange={event => field('action', event.target.value)} /></label><datalist id="audit-actions">{Object.entries(AUDIT_ACTION_LABELS).map(([code, label]) => <option key={code} value={code}>{t(label)}</option>)}</datalist>
          <label className="field"><span>{t('对象类型')}</span><select name="entityType" aria-label={t('对象类型')} value={form.entityType} onChange={event => field('entityType', event.target.value as AuditFilterDraft['entityType'])}><option value="">{t('全部类型')}</option>{AUDIT_ENTITY_TYPES.map(value => <option key={value} value={value}>{t(AUDIT_ENTITY_LABELS[value])}</option>)}</select></label>
          <label className="field"><span>{t('对象 ID')}</span><input name="entityId" inputMode="numeric" maxLength={16} placeholder={t('不限')} value={form.entityId} onChange={event => field('entityId', event.target.value)} /></label>
          <label className="field"><span>{t('开始时间（包含）')}</span><input name="from" type="datetime-local" value={form.from} onChange={event => field('from', event.target.value)} /></label>
          <label className="field"><span>{t('结束时间（不包含）')}</span><input name="to" type="datetime-local" value={form.to} onChange={event => field('to', event.target.value)} /></label>
        </div><div className="audit-filter-bottom"><p>{t('填写后点击查询。时间使用本机时区，动作代码精确匹配且区分大小写。')}</p><div><button type="button" className="button button-secondary" onClick={reset}>{t('重置筛选')}</button><button type="submit" className="button button-primary"><Search size={16} />{t('查询日志')}</button></div></div>
        {validation && <Alert>{t(validation)}</Alert>}
      </form>
      <p className="audit-scroll-hint">{t('左右滑动表格可查看全部字段，详情按钮固定在右侧。')}</p>
      {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
        : loading ? <div className="table-loading"><Spinner label="正在加载审计日志" /></div>
        : <div className="table-scroll"><table className="users-table audit-table" aria-label={t('审计记录')}><thead><tr><th>{t('日志编号')}</th><th>{t('发生时间')}</th><th>{t('操作人')}</th><th>{t('动作')}</th><th>{t('操作对象')}</th><th>{t('操作')}</th></tr></thead><tbody>
          {data?.items.map(log => <tr key={log.id} data-log-id={log.id}><td className="audit-log-id">#{log.id}</td><td className="date-cell"><time dateTime={log.occurredAt} title={log.occurredAt}>{auditTime(log.occurredAt, locale)}</time></td>
            <td><div className="audit-actor"><span>{log.actor?.displayName ?? t('系统操作')}</span>{log.actor && <small>@{log.actor.username} · ID {log.actor.id}</small>}</div></td><td><AuditAction action={log.action} /></td>
            <td><span className="audit-entity">{t(AUDIT_ENTITY_LABELS[log.entityType] || log.entityType)}</span><span className="audit-entity-id">#{log.entityId}</span></td>
            <td><button type="button" className="row-role-button" aria-label={t('查看日志 {id}', { id: log.id })} onClick={() => setSelected(log.id)}><Eye size={15} />{t('详情')}</button></td></tr>)}
          {!data?.items.length && <tr><td colSpan={6}><div className="empty-state"><span><FileSearch size={27} /></span><h3>{t('没有符合条件的审计日志')}</h3><p>{t('调整筛选条件或清除筛选后重试。')}</p></div></td></tr>}
        </tbody></table></div>}
      <div className="table-footer"><div className="page-size"><span>{t('每页')}</span><select aria-label={t('每页日志数')} value={size} disabled={loading} onChange={event => { setSize(Number(event.target.value)); setPage(0) }}><option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select><span>{t('条，共 {count} 条日志', { count })}</span></div><SmallPagination page={page} total={data?.totalPages ?? 0} loading={loading || !!error} onChange={setPage} /></div>
    </section><div className="access-note"><ShieldCheck size={16} /><span>{t('仅管理员可访问。查询不会修改日志，也不会产生新的审计记录。')}</span></div>
    {selected !== null && <AuditLogDialog key={selected} id={selected} onClose={() => setSelected(null)} />}
  </div>
}
