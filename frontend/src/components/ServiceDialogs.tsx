import { ChevronLeft, ChevronRight, LoaderCircle, RefreshCw, Boxes } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useI18n } from '../i18n/I18nContext'
import { api, ApiError, errorMessage } from '../lib/api'
import type { ServiceOwner, SoftwareService, UserPage } from '../types'
import { Alert, Dialog } from './ui'

export function ServiceEditor({ service, onClose, onSaved }: {
  service?: SoftwareService; onClose: () => void; onSaved: (service: SoftwareService) => void
}) {
  const { t } = useI18n()
  const [code, setCode] = useState(service?.code || '')
  const [name, setName] = useState(service?.name || '')
  const [description, setDescription] = useState(service?.description || '')
  const [owner, setOwner] = useState<ServiceOwner | null>(service?.owner || null)
  const [ownerPage, setOwnerPage] = useState(0)
  const [owners, setOwners] = useState<UserPage | null>(null)
  const [ownerLoading, setOwnerLoading] = useState(true)
  const [ownerError, setOwnerError] = useState(false)
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})

  useEffect(() => {
    const controller = new AbortController()
    setOwnerLoading(true); setOwnerError(false)
    api.users(ownerPage, 10, controller.signal).then(setOwners).catch(() => {
      if (!controller.signal.aborted) setOwnerError(true)
    }).finally(() => { if (!controller.signal.aborted) setOwnerLoading(false) })
    return () => controller.abort()
  }, [ownerPage, revision])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setError(''); setFields({})
    if (!name.trim()) { setError('服务名称不能为空白。'); return }
    if (owner && !owner.enabled) { setError('请选择启用的负责人，或清空负责人。'); return }
    setBusy(true)
    try {
      const body = { code, name: name.trim(), description: description.trim() || null, ownerId: owner?.id ?? null }
      onSaved(service ? await api.updateService(service.id, body) : await api.createService(body))
    } catch (failure) {
      setError(errorMessage(failure))
      if (failure instanceof ApiError) setFields(failure.fields)
    } finally { setBusy(false) }
  }

  const options = (owners?.items || []).filter(user => user.enabled)
  const selectedOnPage = options.some(user => user.id === owner?.id)
  const fieldError = (field: string) => fields[field] && <small className="field-error">{t('请检查该字段的格式或长度。')}</small>
  return <Dialog title={service ? '编辑服务' : '新建服务'} description="填写服务信息，可选择启用的用户作为负责人。" busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">
      {error && <Alert>{t(error)}</Alert>}
      <label className="field"><span>{t('服务编码')}</span><input name="code" value={code} onChange={event => setCode(event.target.value)} placeholder={t('例如：payment-api')}
        pattern={'[A-Za-z0-9][A-Za-z0-9._\\-]{0,49}'} maxLength={50} required disabled={busy} aria-invalid={!!fields.code} />
        <small className="field-hint">{t('编码由 1–50 个字母、数字、点、下划线或短横线组成，首字符须为字母或数字。')}</small>{fieldError('code')}</label>
      <label className="field"><span>{t('服务名称')}</span><input name="name" value={name} onChange={event => setName(event.target.value)} placeholder={t('例如：支付服务')}
        maxLength={100} required disabled={busy} aria-invalid={!!fields.name} />{fieldError('name')}</label>
      <label className="field"><span>{t('服务描述')}</span><textarea name="description" value={description} onChange={event => setDescription(event.target.value)}
        placeholder={t('描述服务的职责与范围（可选）')} rows={3} maxLength={10000} disabled={busy} aria-invalid={!!fields.description} />{fieldError('description')}</label>
      <div className="field"><label htmlFor="service-owner">{t('负责人')}</label><select id="service-owner" name="ownerId" value={owner?.id ?? ''} disabled={busy || ownerLoading || ownerError}
        onChange={event => setOwner(options.find(user => user.id === Number(event.target.value)) || null)} aria-invalid={!!fields.ownerId}>
        <option value="">{t('不分配负责人')}</option>
        {owner && !selectedOnPage && <option value={owner.id}>{owner.displayName} (@{owner.username}){!owner.enabled ? ` · ${t('已停用')}` : ''}</option>}
        {options.map(user => <option key={user.id} value={user.id}>{user.displayName} (@{user.username})</option>)}
      </select>{fieldError('ownerId')}
        {ownerLoading && <small role="status">{t('正在加载负责人…')}</small>}
        {ownerError && <div className="owner-error"><span role="alert">{t('负责人加载失败，可重试。')}</span><button type="button" className="text-link" disabled={busy} onClick={() => setRevision(value => value + 1)}><RefreshCw size={14} />{t('重新加载')}</button></div>}
        <small className="field-hint">{t('仅列出启用账号，可翻页查找其他用户；未更改时保留当前负责人。')}</small>
        <div className="owner-pagination"><span>{t('用户第 {page} / {total} 页', { page: ownerPage + 1, total: Math.max(owners?.totalPages || 1, 1) })}</span>
          <button type="button" className="icon-button" aria-label={t('上一批用户')} disabled={busy || ownerLoading || ownerPage === 0} onClick={() => setOwnerPage(value => value - 1)}><ChevronLeft size={16} /></button>
          <button type="button" className="icon-button" aria-label={t('下一批用户')} disabled={busy || ownerLoading || ownerError || !owners || ownerPage + 1 >= owners.totalPages} onClick={() => setOwnerPage(value => value + 1)}><ChevronRight size={16} /></button></div>
        {owner && !owner.enabled && <Alert info>{t('当前负责人已停用，请更换或清空负责人。')}</Alert>}
        {owner && ownerError && <button type="button" className="text-link" disabled={busy} onClick={() => setOwner(null)}>{t('不分配负责人')}</button>}
      </div>
    </div><div className="dialog-footer"><button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>{t('取消')}</button>
      <button type="submit" className="button button-primary" disabled={busy}>{busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : service ? '保存服务' : '创建服务')}</button></div></form>
  </Dialog>
}

export function ServiceStatusDialog({ service, onClose, onSaved }: { service: SoftwareService; onClose: () => void; onSaved: (service: SoftwareService) => void }) {
  const { t } = useI18n()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save() {
    if (busy) return
    setBusy(true); setError('')
    try { onSaved(await api.updateServiceStatus(service.id, !service.enabled)) }
    catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  return <Dialog title={service.enabled ? '停用服务' : '启用服务'} onClose={onClose} busy={busy}>
    <div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <span className={`confirmation-icon ${service.enabled ? 'confirmation-warning' : ''}`}><Boxes size={25} /></span>
      <p className="confirmation-text">{t(service.enabled ? '确定要停用服务 {name} 吗？' : '确定要启用服务 {name} 吗？', { name: service.name })}</p>
      <p className="section-description">{t(service.enabled ? '停用不会删除服务、事件、变更或审计记录。' : '如已分配负责人，启用时该用户必须仍处于启用状态。')}</p>
    </div><div className="dialog-footer"><button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>{t('取消')}</button>
      <button type="button" className={`button ${service.enabled ? 'button-danger' : 'button-primary'}`} disabled={busy} onClick={() => void save()}>
        {busy && <LoaderCircle size={16} className="spin" />}{t(busy ? '正在保存…' : service.enabled ? '确认停用' : '确认启用')}</button></div>
  </Dialog>
}
