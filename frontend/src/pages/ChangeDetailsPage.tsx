import { ArrowLeft, CheckCheck, Pencil, Play, RefreshCw, Send, X } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { ChangeActionDialog, ChangeEditor } from '../components/ChangeDialogs'
import type { ChangeAction } from '../components/ChangeDialogs'
import { ChangeStatusBadge } from '../components/ChangeUi'
import { SeverityBadge, SmallPagination } from '../components/IncidentUi'
import { Alert, formatDate, Spinner, Toast } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import { usePagedActivity } from '../lib/usePagedActivity'
import type { ChangeRequest } from '../types'

export function ChangeDetailsPage() {
  const { id } = useParams()
  const numericId = Number(id), valid = !!id && /^[1-9]\d*$/.test(id) && Number.isSafeInteger(numericId)
  const { t, locale } = useI18n()
  const location = useLocation()
  const [change, setChange] = useState<ChangeRequest | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [modal, setModal] = useState<'edit' | ChangeAction | null>(null)
  const [reviewPage, setReviewPage] = useState(0), [historyPage, setHistoryPage] = useState(0)
  const [toast, setToast] = useState(location.state?.notice === '变更草稿已创建。' ? '变更草稿已创建。' : '')
  const closeToast = useCallback(() => setToast(''), [])
  const reviews = usePagedActivity(api.changeReviews, numericId, reviewPage, revision, valid)
  const history = usePagedActivity(api.changeHistory, numericId, historyPage, revision, valid)
  useEffect(() => { setModal(null); setReviewPage(0); setHistoryPage(0) }, [id])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    if (!valid) { setError('变更申请不存在'); setChange(null); setLoading(false); return }
    api.change(numericId, controller.signal).then(result => { if (!controller.signal.aborted) setChange(result) })
      .catch((failure: unknown) => { if (!controller.signal.aborted) { setError(errorMessage(failure)); setChange(null) } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [numericId, valid, revision])
  function saved(updated: ChangeRequest) { setChange(updated); setModal(null); setToast('变更已更新。'); setReviewPage(0); setHistoryPage(0); setRevision(value => value + 1) }
  const retry = () => setRevision(value => value + 1)
  return <div className="page-enter"><Link className="text-link back-link" to="/changes"><ArrowLeft size={16} />{t('返回变更列表')}</Link>
    <div className="page-heading"><div><span className="eyebrow">CHANGE DETAILS</span><h1>{t('变更详情')}</h1><p>{change && !loading ? `CHG-${String(change.id).padStart(4, '0')}` : '—'}</p></div>
      <button className="button button-secondary" disabled={loading} onClick={retry}><RefreshCw size={16} className={loading ? 'spin' : ''} />{t('刷新详情')}</button></div>
    {error ? <Alert>{t(error)}</Alert> : loading ? <div className="surface table-loading"><Spinner label="正在加载详情" /></div> : change && <>
      <section className="surface incident-summary"><div className="incident-detail-title"><div><h2>{change.title}</h2><Link className="text-link" to={`/services/${change.service.id}`}>{change.service.name} · {change.service.code}</Link></div>
        {change.riskLevel && <SeverityBadge severity={change.riskLevel} />}<ChangeStatusBadge status={change.status} /></div>
        <div className="change-round">{t('提交轮次：{number}', { number: change.submissionNumber })}</div>
        <div className="change-plan-grid">{([['业务原因', change.businessReason], ['实施计划', change.implementationPlan], ['回滚计划', change.rollbackPlan]] as const).map(([label, value]) => <div className="service-description" key={label}><h3>{t(label)}</h3><p>{value || t('尚未填写')}</p></div>)}</div>
        {change.implementationResult && <div className={`incident-resolution ${change.status === 'FAILED' ? 'change-failed-result' : ''}`}><h3>{t('实施结果说明')}</h3><p>{change.implementationResult}</p>
          {change.failureIncidentId && <Link className="text-link" to={`/incidents/${change.failureIncidentId}`}>{t('查看失败关联事件 #{id}', { id: change.failureIncidentId })}</Link>}</div>}
        <dl className="service-facts"><div><dt>{t('申请人')}</dt><dd>{change.requester.displayName} (@{change.requester.username})</dd></div>
          <div><dt>{t('计划实施时间')}</dt><dd>{change.plannedImplementationAt ? formatDate(change.plannedImplementationAt, true, locale) : t('尚未填写')}</dd></div>
          <div><dt>{t('创建时间')}</dt><dd>{formatDate(change.createdAt, true, locale)}</dd></div><div><dt>{t('更新时间')}</dt><dd>{formatDate(change.updatedAt, true, locale)}</dd></div>
          {change.submittedAt && <div><dt>{t('提交时间')}</dt><dd>{formatDate(change.submittedAt, true, locale)}</dd></div>}
          {change.implementationStartedAt && <div><dt>{t('实施开始时间')}</dt><dd>{formatDate(change.implementationStartedAt, true, locale)}</dd></div>}
          {change.implementationFinishedAt && <div><dt>{t('实施结束时间')}</dt><dd>{formatDate(change.implementationFinishedAt, true, locale)}</dd></div>}
          {change.relatedIncidentId && <div><dt>{t('关联事件（可选）')}</dt><dd><Link className="text-link" to={`/incidents/${change.relatedIncidentId}`}>INC-{change.relatedIncidentId}</Link></dd></div>}</dl>
        <div className="incident-actions">
          {change.permissions.canEdit && <button className="button button-secondary" onClick={() => setModal('edit')}><Pencil size={16} />{t('编辑变更草稿')}</button>}
          {change.permissions.canSubmit && <button className="button button-primary" onClick={() => setModal('submit')}><Send size={16} />{t('提交审批')}</button>}
          {change.permissions.canReview && <><button className="button button-primary" onClick={() => setModal('approve')}><CheckCheck size={16} />{t('批准变更')}</button><button className="button button-danger" onClick={() => setModal('reject')}><X size={16} />{t('拒绝变更')}</button></>}
          {change.permissions.canStart && <button className="button button-primary" onClick={() => setModal('start')}><Play size={16} />{t('开始实施')}</button>}
          {change.permissions.canFinish && <><button className="button button-primary" onClick={() => setModal('complete')}>{t('记录实施成功')}</button><button className="button button-danger" onClick={() => setModal('fail')}>{t('记录实施失败')}</button></>}
        </div>
      </section><div className="incident-detail-grid"><section className="surface incident-panel" aria-label={t('审批记录')}><div className="section-title"><h2>{t('审批记录')}</h2></div>
        {reviews.error ? <><Alert>{t(reviews.error)}</Alert><button className="text-link" onClick={retry}>{t('重新加载')}</button></> : reviews.loading ? <Spinner /> : <div className="incident-notes">{reviews.data?.items.map(item => <article key={item.id}>
          <header><strong>{item.reviewer.displayName}</strong><ChangeStatusBadge status={item.decision} /></header><div className="change-review-meta">{t('提交轮次：{number}', { number: item.submissionNumber })} · {formatDate(item.reviewedAt, true, locale)}</div>
          {item.comment && <p>{item.comment}</p>}</article>)}{!reviews.data?.items.length && <p className="section-description">{t('暂无审批记录。')}</p>}</div>}
        <SmallPagination page={reviewPage} total={reviews.data?.totalPages || 0} loading={reviews.loading || !!reviews.error} onChange={setReviewPage} />
      </section><section className="surface incident-panel" aria-label={t('状态历史')}><div className="section-title"><h2>{t('状态历史')}</h2></div>
        {history.error ? <><Alert>{t(history.error)}</Alert><button className="text-link" onClick={retry}>{t('重新加载')}</button></> : history.loading ? <Spinner /> : <ol className="incident-timeline">{history.data?.items.map(item => <li key={item.id}><ChangeStatusBadge status={item.newStatus} />
          <p>{item.changedBy.displayName} · {formatDate(item.changedAt, true, locale)}</p>{item.comment && <p className="timeline-comment">{item.comment}</p>}</li>)}</ol>}
        <SmallPagination page={historyPage} total={history.data?.totalPages || 0} loading={history.loading || !!history.error} onChange={setHistoryPage} />
      </section></div>
    </>}
    {change && modal === 'edit' && <ChangeEditor change={change} onClose={() => setModal(null)} onSaved={saved} />}
    {change && modal && modal !== 'edit' && <ChangeActionDialog change={change} action={modal} onClose={() => setModal(null)} onSaved={saved} />}
    {toast && <Toast message={toast} onClose={closeToast} />}
  </div>
}
