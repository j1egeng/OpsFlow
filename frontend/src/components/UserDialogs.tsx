import { useI18n } from '../i18n/I18nContext'
import { Eye, EyeOff, LoaderCircle, ShieldCheck, UserPlus } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError, errorMessage } from '../lib/api'
import { ROLE_CODES, ROLE_DESCRIPTIONS, ROLE_LABELS } from '../types'
import type { CreateUserRequest, Role, User } from '../types'
import { Alert, Avatar, Dialog } from './ui'

function RolePicker({ value, onChange, disabled = false }: { value: Role[]; onChange: (roles: Role[]) => void; disabled?: boolean }) {
  const { t } = useI18n()
  return <fieldset className="role-picker" disabled={disabled}><legend>{t("账户角色")}<span>{t("可多选")}</span></legend>
    {ROLE_CODES.map(role => <label key={role} className={`role-option ${value.includes(role) ? 'role-selected' : ''}`}>
      <input type="checkbox" aria-label={t(ROLE_LABELS[role])} checked={value.includes(role)} onChange={event => onChange(event.target.checked ? [...value, role] : value.filter(item => item !== role))} />
      <span><strong>{t(ROLE_LABELS[role])}</strong><small>{t(ROLE_DESCRIPTIONS[role])}</small></span></label>)}
  </fieldset>
}

function SubmitButton({ busy, children }: { busy: boolean; children: string }) {
  const { t } = useI18n()
  return <button className="button button-primary" type="submit" disabled={busy}>{busy && <LoaderCircle size={16} className="spin" />}{busy ? t("正在保存…") : children}</button>
}

export function CreateUserDialog({ onClose, onSaved }: { onClose: () => void; onSaved: (user: User) => void }) {
  const { t } = useI18n()
  const [form, setForm] = useState<CreateUserRequest>({ username: '', displayName: '', email: '', password: '', roles: ['DEVELOPER'] })
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  function change(field: keyof Omit<CreateUserRequest, 'roles'>, value: string) { setForm(previous => ({ ...previous, [field]: value })) }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setFields({}); setError('')
    if (!form.roles.length) { setError('请至少选择一种账户角色。'); return }
    if (new TextEncoder().encode(form.password).byteLength > 72) { setError('密码的 UTF-8 编码长度不能超过 72 字节。'); return }
    setBusy(true)
    try {
      const created = await api.createUser({ ...form, username: form.username.trim(), email: form.email.trim(), displayName: form.displayName.trim() })
      setForm(previous => ({ ...previous, password: '' }))
      onSaved(created)
    } catch (failure) { setError(errorMessage(failure)); if (failure instanceof ApiError) setFields(failure.fields) }
    finally { setBusy(false) }
  }
  return <Dialog title={t("新建用户")} description={t("为团队成员创建账号，并分配合适的访问角色。")} busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <div className="form-grid"><label className="field"><span>{t("姓名")}</span><input name="displayName" placeholder={t("例如：张明")} autoComplete="off" value={form.displayName} onChange={event => change('displayName', event.target.value)} maxLength={100} required disabled={busy} aria-invalid={!!fields.displayName} />{fields.displayName && <small className="field-error">{t("请检查该字段的格式或长度。")}</small>}</label>
        <label className="field"><span>{t("用户名")}</span><input name="username" placeholder={t("例如：zhang.ming")} autoComplete="off" value={form.username} onChange={event => change('username', event.target.value)} pattern={'[A-Za-z0-9][A-Za-z0-9._\\-]{2,49}'} minLength={3} maxLength={50} required disabled={busy} aria-invalid={!!fields.username} />{fields.username && <small className="field-error">{t("请检查该字段的格式或长度。")}</small>}</label></div>
      <label className="field"><span>{t("邮箱地址")}</span><input name="email" type="email" autoComplete="off" placeholder="name@example.com" value={form.email} onChange={event => change('email', event.target.value)} maxLength={254} required disabled={busy} aria-invalid={!!fields.email} />{fields.email && <small className="field-error">{t("请检查该字段的格式或长度。")}</small>}</label>
      <div className="field"><label htmlFor="new-password">{t("初始密码")}</label><div className="password-field"><input id="new-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" placeholder={t("至少 12 个字符")} value={form.password} onChange={event => change('password', event.target.value)} minLength={12} maxLength={72} required disabled={busy} aria-invalid={!!fields.password} />
        <button type="button" className="password-toggle" aria-label={showPassword ? t("隐藏初始密码") : t("显示初始密码")} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></div>
        <small className="field-hint">{t("至少 12 个字符，UTF-8 编码后不超过 72 字节。")}</small>{fields.password && <small className="field-error">{t("请检查该字段的格式或长度。")}</small>}</div>
      <RolePicker value={form.roles} onChange={roles => setForm(previous => ({ ...previous, roles }))} disabled={busy} />
    </div><div className="dialog-footer"><button className="button button-secondary" type="button" onClick={onClose} disabled={busy}>{t("取消")}</button><SubmitButton busy={busy}>{t("创建用户")}</SubmitButton></div></form>
  </Dialog>
}

export function RolesDialog({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: (user: User) => void }) {
  const { t } = useI18n()
  const [roles, setRoles] = useState<Role[]>(user.roles)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (!roles.length) { setError('请至少保留一种账户角色。'); return }
    setBusy(true); setError('')
    try { onSaved(await api.updateRoles(user.id, roles)) }
    catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  return <Dialog title={t("编辑角色")} description={t("角色变更会在该用户下一次访问时生效。")} busy={busy} onClose={onClose}>
    <form onSubmit={event => void submit(event)}><div className="dialog-body">{error && <Alert>{t(error)}</Alert>}
      <div className="dialog-person"><Avatar name={user.displayName} /><div><strong>{user.displayName}</strong><span>@{user.username}</span></div><ShieldCheck size={21} /></div>
      <RolePicker value={roles} onChange={setRoles} disabled={busy} /></div>
      <div className="dialog-footer"><button className="button button-secondary" type="button" onClick={onClose} disabled={busy}>{t("取消")}</button><SubmitButton busy={busy}>{t("保存角色")}</SubmitButton></div></form>
  </Dialog>
}

export function StatusDialog({ user, self, onClose, onSaved }: { user: User; self: boolean; onClose: () => void; onSaved: (user: User) => void }) {
  const { t } = useI18n()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save() {
    if (busy) return
    setBusy(true); setError('')
    try { onSaved(await api.updateStatus(user.id, !user.enabled)) }
    catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }
  return <Dialog title={user.enabled ? '停用账号' : '启用账号'} busy={busy} onClose={onClose}>
    <div className="dialog-body">{error && <Alert>{t(error)}</Alert>}<span className={`confirmation-icon ${user.enabled ? 'confirmation-warning' : ''}`}><UserPlus size={25} /></span>
      <p className="confirmation-text">{t(user.enabled ? '确定要停用 {name}（@{username}）的账号吗？' : '确定要启用 {name}（@{username}）的账号吗？', { name: user.displayName, username: user.username })}</p>
      <p className="section-description">{user.enabled ? t("停用后，该用户将无法登录或继续访问工作台。账号资料与历史记录会保留。") : t("启用后，该用户可以使用原账号和密码登录工作台。")}{self && user.enabled ? t("这也会结束你当前的登录会话。") : ''}</p>
    </div><div className="dialog-footer"><button className="button button-secondary" type="button" onClick={onClose} disabled={busy}>{t("取消")}</button>
      <button className={`button ${user.enabled ? 'button-danger' : 'button-primary'}`} type="button" onClick={() => void save()} disabled={busy}>{busy && <LoaderCircle size={16} className="spin" />}{busy ? t("正在保存…") : user.enabled ? t("确认停用") : t("确认启用")}</button></div>
  </Dialog>
}
