import { LoaderCircle } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { useI18n } from '../i18n/I18nContext'
import { api, ApiError, errorMessage } from '../lib/api'
import { dateTimeInstant, localDateTime } from '../lib/change-form'
import type { ChangeRequest, RiskLevel } from '../types'
import { Alert, Dialog } from './ui'
import { ResourcePicker } from './IncidentDialogs'
import { SEVERITY_LABELS } from './IncidentUi'

export function ChangeEditor({ change, onClose, onSaved }: { change?: ChangeRequest; onClose: () => void; onSaved: (change: ChangeRequest) => void }) {
  const { t } = useI18n()
  const [serviceId, setServiceId] = useState<number | null>(change?.service.id || null)
  const [relatedId, setRelatedId] = useState<number | null>(change?.relatedIncidentId || null)
  const [title, setTitle] = useState(change?.title || '')
  const [businessReason, setBusinessReason] = useState(change?.businessReason || '')
  const [risk, setRisk] = useState<RiskLevel | ''>(change?.riskLevel || '')
  const [planned, setPlanned] = useState(localDateTime(change?.plannedImplementationAt || null))
  const [plan, setPlan] = useState(change?.implementationPlan || '')
  const [rollback, setRollback] = useState(change?.rollbackPlan || '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (!serviceId || !title.trim()) { setError('请选择服务并填写变更标题。'); return }
    setBusy(true); setError('')
    try {
      const body = { serviceId, relatedIncidentId: relatedId, title: title.trim(), businessReason: businessReason.trim() || null,
        riskLevel: risk || null, plannedImplementationAt: dateTimeInstant(planned), implementationPlan: plan.trim() || null, rollbackPlan: rollback.trim() || null, version: change?.version }
      onSaved(change ? await api.updateChange(change.id, body) : await api.createChange(body))
    } catch (failure) { setError(errorMessage(failure)); setConflict(failure instanceof ApiError && failure.status === 409) }
    finally { setBusy(false) }
  }
  return <Dialog title={change ? '编辑变更草稿' : '新建变更'} description="可先保存不完整的草稿，提交审批前需补齐全部计划。" busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <ResourcePicker kind="service" value={serviceId} initialLabel={change ? `${change.service.name} (${change.service.code})` : undefined}
        disabled={busy} onChange={id => { setServiceId(id); setRelatedId(null) }} />
      <ResourcePicker key={serviceId} kind="related" serviceId={serviceId || undefined} value={relatedId} initialLabel={relatedId ? `INC-${relatedId}` : undefined} disabled={busy} onChange={setRelatedId} />
      <label className="field"><span>{t('变更标题')}</span><input name="title" value={title} required maxLength={200} disabled={busy} onChange={event => setTitle(event.target.value)} /></label>
      <label className="field"><span>{t('业务原因')}</span><textarea name="businessReason" rows={3} maxLength={10000} value={businessReason} disabled={busy} onChange={event => setBusinessReason(event.target.value)} /></label>
      <div className="form-grid"><label className="field"><span>{t('风险等级')}</span><select name="riskLevel" value={risk} disabled={busy} onChange={event => setRisk(event.target.value as RiskLevel | '')}>
        <option value="">{t('尚未评估')}</option>{(['LOW', 'MEDIUM', 'HIGH'] as const).map(value => <option value={value} key={value}>{t(SEVERITY_LABELS[value])}</option>)}</select></label>
        <label className="field"><span>{t('计划实施时间')}</span><input name="plannedImplementationAt" type="datetime-local" value={planned} disabled={busy} onChange={event => setPlanned(event.target.value)} /></label></div>
      <small className="field-hint">{t('计划时间使用本机时区填写，系统以 UTC 保存。')}</small>
      <label className="field"><span>{t('实施计划')}</span><textarea name="implementationPlan" rows={3} maxLength={10000} value={plan} disabled={busy} onChange={event => setPlan(event.target.value)} /></label>
      <label className="field"><span>{t('回滚计划')}</span><textarea name="rollbackPlan" rows={3} maxLength={10000} value={rollback} disabled={busy} onChange={event => setRollback(event.target.value)} /></label>
      {conflict && <p className="field-hint">{t('请关闭弹窗并刷新详情后重试，避免覆盖其他人的更新。')}</p>}
    </div><div className="dialog-footer"><button className="button button-secondary" type="button" disabled={busy} onClick={onClose}>{t('取消')}</button>
      <button className="button button-primary" type="submit" disabled={busy || conflict}>{busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : '保存草稿')}</button></div></form>
  </Dialog>
}
export type ChangeAction = 'submit' | 'approve' | 'reject' | 'start' | 'complete' | 'fail'
export function ChangeActionDialog({ change, action, onClose, onSaved }: { change: ChangeRequest; action: ChangeAction; onClose: () => void; onSaved: (change: ChangeRequest) => void }) {
  const { t } = useI18n()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const label = { submit: '提交审批', approve: '批准变更', reject: '拒绝变更', start: '开始实施', complete: '记录实施成功', fail: '记录实施失败' }[action]
  const isResult = action === 'complete' || action === 'fail'
  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if ((isResult || action === 'reject') && !text.trim()) { setError(isResult ? '请填写实施结果。' : '拒绝变更时必须填写原因'); return }
    setBusy(true); setError('')
    try {
      let result: ChangeRequest
      if (action === 'submit') result = await api.submitChange(change.id, change.version)
      else if (action === 'start') result = await api.startChange(change.id, change.version)
      else if (isResult) result = await api.finishChange(change.id, change.version, action === 'complete' ? 'COMPLETED' : 'FAILED', text.trim())
      else result = await api.reviewChange(change.id, change.version, action === 'approve' ? 'APPROVED' : 'REJECTED', text.trim() || undefined)
      onSaved(result)
    } catch (failure) { setError(errorMessage(failure)); setConflict(failure instanceof ApiError && failure.status === 409) }
    finally { setBusy(false) }
  }
  return <Dialog title={label} busy={busy} onClose={onClose}><form onSubmit={event => void save(event)}><div className="dialog-body">
    {error && <Alert>{t(error)}</Alert>}<p className="incident-action-title">CHG-{change.id} · {change.title}</p>
    {action === 'submit' && <p className="section-description">{t('提交后资料将锁定，等待其他审批人批准或拒绝。')}</p>}
    {action === 'start' && <p className="section-description">{t('请按批准的实施计划执行，并准备好回滚方案。')}</p>}
    {(isResult || action === 'approve' || action === 'reject') && <label className="field"><span>{t(isResult ? '实施结果说明' : '审批意见')}</span>
      <textarea name={isResult ? 'result' : 'comment'} rows={4} value={text} maxLength={isResult ? 10000 : 2000} disabled={busy} required={isResult || action === 'reject'} onChange={event => setText(event.target.value)} /></label>}
    {action === 'fail' && <Alert info>{t('保存失败结果将自动创建同服务的关联事件，请同时记录回滚和恢复情况。')}</Alert>}
    {conflict && <p className="field-hint">{t('请关闭弹窗并刷新详情后重试，避免覆盖其他人的更新。')}</p>}
  </div><div className="dialog-footer"><button className="button button-secondary" type="button" disabled={busy} onClick={onClose}>{t('取消')}</button>
    <button type="submit" className={`button ${action === 'fail' || action === 'reject' ? 'button-danger' : 'button-primary'}`} disabled={busy || conflict}>{busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : label)}</button></div></form></Dialog>
}
