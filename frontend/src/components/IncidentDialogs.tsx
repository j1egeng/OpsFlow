import { LoaderCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useI18n } from '../i18n/I18nContext'
import { api, ApiError, errorMessage } from '../lib/api'
import { INCIDENT_SEVERITIES } from '../types'
import type { Incident, IncidentSeverity, IncidentStatus } from '../types'
import { Alert, Dialog } from './ui'
import { SEVERITY_LABELS, SmallPagination } from './IncidentUi'

export function ResourcePicker({ kind, value, onChange, disabled, serviceId, initialLabel }: { kind: 'service' | 'assignee' | 'related'; value: number | null; onChange: (id: number | null) => void; disabled: boolean; serviceId?: number; initialLabel?: string }) {
  const { t } = useI18n()
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<{ id: number; label: string }[]>([])
  const [selected, setSelected] = useState<{ id: number; label: string } | null>(value && initialLabel ? { id: value, label: initialLabel } : null)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    const load = async () => {
      if (kind === 'related') {
        if (!serviceId) return { rows: [], total: 0 }
        const result = await api.incidents(page, 10, { serviceId }, controller.signal)
        return { rows: result.items.map(item => ({ id: item.id, label: `INC-${item.id} · ${item.title}` })), total: result.totalPages }
      }
      if (kind === 'service') {
        const result = await api.services(page, 10, true, controller.signal)
        return { rows: result.items.map(item => ({ id: item.id, label: `${item.name} (${item.code})` })), total: result.totalPages }
      }
      const result = await api.incidentAssignees(page, 10, controller.signal)
      return { rows: result.items.map(item => ({ id: item.id, label: `${item.displayName} (@${item.username})` })), total: result.totalPages }
    }
    void load().then(result => { setRows(result.rows); setTotal(result.total) }).catch((failure: unknown) => {
      if (!controller.signal.aborted) setError(errorMessage(failure))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [kind, page, revision, serviceId])
  return <div className="field"><label htmlFor={`incident-${kind}`}>{t(kind === 'service' ? '关联服务' : kind === 'related' ? '关联事件（可选）' : '处理人')}</label>
    <select id={`incident-${kind}`} value={value ?? ''} required={kind !== 'related'} disabled={disabled || loading || !!error || kind === 'related' && !serviceId} onChange={event => {
      const next = rows.find(row => row.id === Number(event.target.value)) || null
      setSelected(next); onChange(next?.id ?? null)
    }}>
      <option value="">{t(loading ? '正在加载可选项…' : kind === 'related' ? '不关联事件' : '请选择')}</option>
      {selected && !rows.some(row => row.id === selected.id) && <option value={selected.id}>{selected.label}</option>}
      {rows.map(row => <option value={row.id} key={row.id}>{row.label}</option>)}
    </select>
    {error && <><Alert>{t(error)}</Alert><button className="text-link" type="button" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></>}
    {!loading && !error && !rows.length && kind !== 'related' && <small>{t(kind === 'service' ? '没有可用服务，请先由管理员创建或启用服务。' : '没有可用处理人，请先创建启用的管理员或开发者账号。')}</small>}
    <small className="field-hint">{t(kind === 'service' ? '仅可选择启用的服务，可翻页查看更多。' : kind === 'related' ? '仅列出当前服务下的事件，可翻页查找。' : '仅可选择启用的管理员或开发者，可翻页查看更多。')}</small>
    <SmallPagination page={page} total={total} loading={loading || disabled || !!error} onChange={setPage} />
  </div>
}

export function CreateIncidentDialog({ onClose, onSaved }: { onClose: () => void; onSaved: (incident: Incident) => void }) {
  const { t } = useI18n()
  const [serviceId, setServiceId] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [severity, setSeverity] = useState<IncidentSeverity>('MEDIUM')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (!serviceId || !title.trim() || !description.trim()) { setError('请选择服务并填写事件标题和描述。'); return }
    setBusy(true); setError('')
    try { onSaved(await api.createIncident({ serviceId, title: title.trim(), description: description.trim(), severity })) }
    catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  return <Dialog title="报告事件" description="关联受影响的服务，并描述问题及严重程度。" busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <ResourcePicker kind="service" value={serviceId} onChange={setServiceId} disabled={busy} />
      <label className="field"><span>{t('事件标题')}</span><input name="title" value={title} onChange={event => setTitle(event.target.value)} required maxLength={200} disabled={busy} placeholder={t('例如：支付接口持续超时')} /></label>
      <label className="field"><span>{t('事件描述')}</span><textarea name="description" value={description} onChange={event => setDescription(event.target.value)} required maxLength={10000} rows={4} disabled={busy} placeholder={t('描述现象、影响范围和已知信息')} /></label>
      <label className="field"><span>{t('严重程度')}</span><select name="severity" value={severity} disabled={busy} onChange={event => setSeverity(event.target.value as IncidentSeverity)}>
        {INCIDENT_SEVERITIES.map(value => <option value={value} key={value}>{t(SEVERITY_LABELS[value])}</option>)}</select></label>
    </div><div className="dialog-footer"><button className="button button-secondary" type="button" disabled={busy} onClick={onClose}>{t('取消')}</button>
      <button className="button button-primary" type="submit" disabled={busy}>{busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : '提交事件')}</button></div></form>
  </Dialog>
}

export function IncidentActionDialog({ incident, action, onClose, onSaved }: { incident: Incident; action: 'assign' | 'investigate' | 'resolve' | 'close'; onClose: () => void; onSaved: (incident: Incident) => void }) {
  const { t } = useI18n()
  const [assigneeId, setAssigneeId] = useState<number | null>(null)
  const [comment, setComment] = useState('')
  const [resolution, setResolution] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const label = { assign: '分配处理人', investigate: '开始调查', resolve: '解决事件', close: '关闭事件' }[action]
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (action === 'assign' && !assigneeId) { setError('请选择处理人。'); return }
    if (action === 'resolve' && !resolution.trim()) { setError('解决事件必须填写解决说明'); return }
    setBusy(true); setError('')
    try {
      const target: IncidentStatus = action === 'investigate' ? 'INVESTIGATING' : action === 'resolve' ? 'RESOLVED' : 'CLOSED'
      onSaved(action === 'assign' ? await api.assignIncident(incident.id, assigneeId!, incident.version, comment.trim() || undefined)
        : await api.transitionIncident(incident.id, target, incident.version, action === 'resolve' ? resolution.trim() : undefined, comment.trim() || undefined))
    } catch (failure) { setError(errorMessage(failure)); setConflict(failure instanceof ApiError && failure.status === 409) }
    finally { setBusy(false) }
  }
  return <Dialog title={label} description="操作将保留在事件历史与审计记录中。" busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <p className="incident-action-title">#{incident.id} · {incident.title}</p>
      {action === 'assign' && <ResourcePicker kind="assignee" value={assigneeId} onChange={setAssigneeId} disabled={busy} />}
      {action === 'resolve' && <label className="field"><span>{t('解决说明')}</span><textarea name="resolution" rows={4} maxLength={10000} required value={resolution} disabled={busy} onChange={event => setResolution(event.target.value)} /></label>}
      <label className="field"><span>{t('操作说明（可选）')}</span><textarea name="comment" rows={3} maxLength={2000} value={comment} disabled={busy} onChange={event => setComment(event.target.value)} /></label>
      {conflict && <p className="field-hint">{t('请关闭弹窗并刷新详情后重试，避免覆盖其他人的更新。')}</p>}
    </div><div className="dialog-footer"><button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>{t('取消')}</button>
      <button type="submit" className="button button-primary" disabled={busy || conflict}>{busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : label)}</button></div></form>
  </Dialog>
}
