import { ArrowLeft, CheckCheck, ClipboardCheck, LoaderCircle, Play, RefreshCw, UserRoundPlus } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { IncidentActionDialog } from '../components/IncidentDialogs'
import { IncidentStatusBadge, SeverityBadge, SmallPagination, STATUS_LABELS } from '../components/IncidentUi'
import { Alert, formatDate, Spinner, Toast } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { INCIDENT_STATUSES } from '../types'
import type { Incident, IncidentHistory, IncidentNote, PageResult } from '../types'

type Action = 'assign' | 'investigate' | 'resolve' | 'close'
export function IncidentDetailsPage() {
  const { id } = useParams()
  const numericId = Number(id)
  const validId = !!id && /^[1-9]\d*$/.test(id) && Number.isSafeInteger(numericId)
  const { t, locale } = useI18n()
  const location = useLocation()
  const [incident, setIncident] = useState<Incident | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [action, setAction] = useState<Action | null>(null)
  const [toast, setToast] = useState(location.state?.notice === '事件已报告。' ? '事件已报告。' : '')
  const closeToast = useCallback(() => setToast(''), [])
  const [notes, setNotes] = useState<PageResult<IncidentNote> | null>(null)
  const [notePage, setNotePage] = useState(0)
  const [notesLoading, setNotesLoading] = useState(true)
  const [notesError, setNotesError] = useState('')
  const [history, setHistory] = useState<PageResult<IncidentHistory> | null>(null)
  const [historyPage, setHistoryPage] = useState(0)
  const [historyLoading, setHistoryLoading] = useState(true)
  const [historyError, setHistoryError] = useState('')
  const [note, setNote] = useState('')
  const [savingNote, setSavingNote] = useState(false)
  const [noteError, setNoteError] = useState('')
  useEffect(() => { setNote(''); setNotePage(0); setHistoryPage(0); setAction(null); setNoteError('') }, [id])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    if (!validId) { setError('事件不存在'); setIncident(null); setLoading(false); return }
    api.incident(numericId, controller.signal).then(setIncident).catch((failure: unknown) => {
      if (!controller.signal.aborted) { setError(errorMessage(failure)); setIncident(null) }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [numericId, validId, revision])
  useEffect(() => {
    if (!validId) return
    const controller = new AbortController()
    setNotesLoading(true); setNotesError('')
    api.incidentNotes(numericId, notePage, 10, controller.signal).then(setNotes).catch((failure: unknown) => {
      if (!controller.signal.aborted) setNotesError(errorMessage(failure))
    }).finally(() => { if (!controller.signal.aborted) setNotesLoading(false) })
    return () => controller.abort()
  }, [numericId, validId, notePage, revision])
  useEffect(() => {
    if (!validId) return
    const controller = new AbortController()
    setHistoryLoading(true); setHistoryError('')
    api.incidentHistory(numericId, historyPage, 10, controller.signal).then(setHistory).catch((failure: unknown) => {
      if (!controller.signal.aborted) setHistoryError(errorMessage(failure))
    }).finally(() => { if (!controller.signal.aborted) setHistoryLoading(false) })
    return () => controller.abort()
  }, [numericId, validId, historyPage, revision])

  function saved(updated: Incident) {
    setIncident(updated); setAction(null); setToast('事件已更新。'); setHistoryPage(0); setRevision(value => value + 1)
  }
  async function addNote(event: FormEvent) {
    event.preventDefault()
    if (!incident || savingNote) return
    if (!note.trim()) { setNoteError('备注不能为空白。'); return }
    setSavingNote(true); setNoteError('')
    try {
      await api.addIncidentNote(incident.id, note.trim(), incident.version)
      setNote(''); setNotePage(0); setToast('调查备注已添加。'); setRevision(value => value + 1)
    } catch (failure) { setNoteError(errorMessage(failure)) } finally { setSavingNote(false) }
  }

  return <div className="page-enter"><Link className="text-link back-link" to="/incidents"><ArrowLeft size={16} />{t('返回事件列表')}</Link>
    <div className="page-heading"><div><span className="eyebrow">INCIDENT DETAILS</span><h1>{t('事件详情')}</h1><p>{incident && !loading ? `INC-${String(incident.id).padStart(4, '0')}` : '—'}</p></div>
      <button className="button button-secondary" disabled={loading || savingNote} onClick={() => setRevision(value => value + 1)}><RefreshCw size={16} className={loading ? 'spin' : ''} />{t('刷新详情')}</button></div>
    {error ? <Alert>{t(error)}</Alert> : loading ? <div className="surface table-loading"><Spinner label="正在加载详情" /></div> : incident && <>
      <section className="surface incident-summary"><div className="incident-detail-title"><div><h2>{incident.title}</h2><Link className="text-link" to={`/services/${incident.service.id}`}>{incident.service.name} · {incident.service.code}</Link></div>
        <SeverityBadge severity={incident.severity} /><IncidentStatusBadge status={incident.status} /></div>
        <ol className="incident-progress" aria-label={t('事件处理流程')}>{INCIDENT_STATUSES.map((state, index) => <li key={state} className={index <= INCIDENT_STATUSES.indexOf(incident.status) ? 'step-reached' : ''} aria-current={state === incident.status ? 'step' : undefined}>
          <span>{index + 1}</span><strong>{t(STATUS_LABELS[state])}</strong></li>)}</ol>
        <div className="service-description"><h3>{t('事件描述')}</h3><p>{incident.description}</p></div>
        {incident.sourceChangeId && <div className="change-source-link"><Link className="text-link" to={`/changes/${incident.sourceChangeId}`}>{t('查看来源变更 #{id}', { id: incident.sourceChangeId })}</Link></div>}
        {incident.resolution && <div className="incident-resolution"><h3><CheckCheck size={17} />{t('解决说明')}</h3><p>{incident.resolution}</p></div>}
        <dl className="service-facts"><div><dt>{t('报告人')}</dt><dd>{incident.reporter.displayName} (@{incident.reporter.username})</dd></div>
          <div><dt>{t('处理人')}</dt><dd>{incident.assignee ? `${incident.assignee.displayName} (@${incident.assignee.username})${incident.assignee.enabled ? '' : ` · ${t('已停用')}`}` : t('未分配')}</dd></div>
          <div><dt>{t('创建时间')}</dt><dd>{formatDate(incident.createdAt, true, locale)}</dd></div><div><dt>{t('更新时间')}</dt><dd>{formatDate(incident.updatedAt, true, locale)}</dd></div>
          {incident.resolvedAt && <div><dt>{t('解决时间')}</dt><dd>{formatDate(incident.resolvedAt, true, locale)}</dd></div>}
          {incident.closedAt && <div><dt>{t('关闭时间')}</dt><dd>{formatDate(incident.closedAt, true, locale)}</dd></div>}</dl>
        <div className="incident-actions">
          {incident.permissions.canAssign && <button className="button button-secondary" disabled={savingNote} onClick={() => setAction('assign')}><UserRoundPlus size={16} />{t(incident.assignee ? '重新分配' : '分配处理人')}</button>}
          {incident.permissions.canInvestigate && <button className="button button-primary" disabled={savingNote} onClick={() => setAction('investigate')}><Play size={16} />{t('开始调查')}</button>}
          {incident.permissions.canResolve && <button className="button button-primary" disabled={savingNote} onClick={() => setAction('resolve')}><CheckCheck size={16} />{t('解决事件')}</button>}
          {incident.permissions.canClose && <button className="button button-primary" disabled={savingNote} onClick={() => setAction('close')}><ClipboardCheck size={16} />{t('关闭事件')}</button>}
        </div>
      </section>
      <div className="incident-detail-grid"><section className="surface incident-panel" aria-label={t('调查备注')}><div className="section-title"><h2>{t('调查备注')}</h2></div>
        {incident.permissions.canAddNote && <form className="incident-note-form" onSubmit={event => void addNote(event)}>{noteError && <Alert>{t(noteError)}</Alert>}
          <label className="field"><span>{t('追加调查备注')}</span><textarea name="note" rows={3} value={note} onChange={event => setNote(event.target.value)} required maxLength={10000} disabled={savingNote} placeholder={t('记录排查进展、发现和验证结果')} /></label>
          <button className="button button-primary" type="submit" disabled={savingNote}>{savingNote && <LoaderCircle size={16} className="spin" />}{t(savingNote ? '正在保存…' : '添加备注')}</button></form>}
        {notesError ? <div className="incident-panel-error"><Alert>{t(notesError)}</Alert><button className="text-link" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
          : notesLoading ? <Spinner /> : <div className="incident-notes">{notes?.items.map(item => <article key={item.id}><header><strong>{item.author.displayName}</strong><time>{formatDate(item.createdAt, true, locale)}</time></header><p>{item.content}</p></article>)}
            {!notes?.items.length && <p className="section-description">{t('暂无调查备注。')}</p>}</div>}
        <SmallPagination page={notePage} total={notes?.totalPages || 0} loading={notesLoading || !!notesError} onChange={setNotePage} />
      </section><section className="surface incident-panel" aria-label={t('状态历史')}><div className="section-title"><h2>{t('状态历史')}</h2></div>
        {historyError ? <div className="incident-panel-error"><Alert>{t(historyError)}</Alert><button className="text-link" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
          : historyLoading ? <Spinner /> : <ol className="incident-timeline">{history?.items.map(item => <li key={item.id}><div><IncidentStatusBadge status={item.newStatus} />{item.oldStatus && <small>{t('原状态：{state}', { state: t(STATUS_LABELS[item.oldStatus]) })}</small>}</div>
            <p>{item.changedBy.displayName} · {formatDate(item.changedAt, true, locale)}</p>{item.comment && <p className="timeline-comment">{item.comment}</p>}</li>)}</ol>}
        <SmallPagination page={historyPage} total={history?.totalPages || 0} loading={historyLoading || !!historyError} onChange={setHistoryPage} />
      </section></div>
    </>}
    {incident && action && <IncidentActionDialog incident={incident} action={action} onClose={() => setAction(null)} onSaved={saved} />}
    {toast && <Toast message={toast} onClose={closeToast} />}
  </div>
}
