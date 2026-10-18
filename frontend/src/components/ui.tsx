import { Activity, AlertCircle, Check, LoaderCircle, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { ROLE_LABELS } from '../types'
import type { Role } from '../types'
import { useI18n } from '../i18n/I18nContext'
import { translate } from '../i18n/messages'
import type { Locale } from '../i18n/messages'
import { LanguageSwitcher } from './LanguageSwitcher'

export function Brand({ light = false }: { light?: boolean }) {
  return <div className={`brand ${light ? 'brand-light' : ''}`}>
    <span className="brand-mark"><Activity size={24} strokeWidth={2.4} /></span>
    <span>OpsFlow<span className="brand-dot">.</span></span>
  </div>
}

export function Spinner({ label = '正在加载' }: { label?: string }) {
  const { t } = useI18n()
  return <div className="loading-state" role="status"><LoaderCircle className="spin" size={24} /><span>{t(label)}</span></div>
}

export function Alert({ children, info = false }: { children: ReactNode; info?: boolean }) {
  return <div className={`alert ${info ? 'alert-info' : ''}`} role={info ? 'status' : 'alert'}>
    <AlertCircle size={18} /><div>{children}</div>
  </div>
}

export function RoleBadges({ roles }: { roles: Role[] }) {
  const { t } = useI18n()
  return <div className="role-badges">{roles.map(role => <span className={`role-badge role-${role.toLowerCase()}`} key={role}>{t(ROLE_LABELS[role])}</span>)}</div>
}

export function Avatar({ name, large = false }: { name: string; large?: boolean }) {
  const initial = name.trim().slice(0, 1).toUpperCase() || 'U'
  return <span className={`avatar ${large ? 'avatar-large' : ''}`} aria-hidden="true">{initial}</span>
}

export function StatusBadge({ enabled }: { enabled: boolean }) {
  const { t } = useI18n()
  return <span className={`status-badge ${enabled ? 'status-active' : 'status-inactive'}`}><i />{t(enabled ? '已启用' : '已停用')}</span>
}

export function Dialog({ title, description, children, onClose, busy = false }:
  { title: string; description?: string; children: ReactNode; onClose: () => void; busy?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null)
  const { t } = useI18n()
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close() }, [])
  return <dialog ref={ref} className="dialog" aria-labelledby="dialog-title" aria-describedby={description ? 'dialog-description' : undefined}
    onCancel={event => { event.preventDefault(); if (!busy) onClose() }}
    onClick={event => { if (event.target === ref.current && !busy) onClose() }}>
    <div className="dialog-header"><div><h2 id="dialog-title">{t(title)}</h2>{description && <p id="dialog-description">{t(description)}</p>}</div>
      <div className="dialog-tools"><LanguageSwitcher /><button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label={t('关闭对话框')}><X size={20} /></button></div></div>
    {children}
  </dialog>
}

export function Toast({ message, onClose }: { message: string; onClose: () => void }) {
  const { t } = useI18n()
  useEffect(() => { const timeout = setTimeout(onClose, 4500); return () => clearTimeout(timeout) }, [message, onClose])
  return <div className="toast" role="status"><span className="toast-icon"><Check size={16} /></span>{t(message)}
    <button className="icon-button" onClick={onClose} aria-label={t('关闭提示')}><X size={16} /></button></div>
}

export function formatDate(value: string | null, withTime = false, locale: Locale = 'zh-CN') {
  if (!value) return translate(locale, '尚未登录')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit',
    ...(withTime ? { hour: '2-digit' as const, minute: '2-digit' as const } : {}) }).format(date)
}
