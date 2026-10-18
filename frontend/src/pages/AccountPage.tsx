import { useI18n } from '../i18n/I18nContext'
import { ArrowUpRight, CalendarDays, Fingerprint, Mail, RefreshCw, ShieldCheck, UserRound } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Alert, Avatar, formatDate, RoleBadges, StatusBadge } from '../components/ui'
import { errorMessage } from '../lib/api'
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from '../types'

export function AccountPage() {
  const { t, locale } = useI18n()
  const { user, refreshUser } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  if (!user) return null
  async function refresh() {
    setBusy(true); setError('')
    try { await refreshUser() } catch (failure) { setError(errorMessage(failure)) } finally { setBusy(false) }
  }
  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">YOUR PROFILE</span><h1>{t("我的账户")}</h1><p>{t("查看你的身份信息与当前访问角色。")}</p></div>
    <button className="button button-secondary" disabled={busy} onClick={() => void refresh()}><RefreshCw size={16} className={busy ? 'spin' : ''} />{t("刷新信息")}</button></div>
    {error && <Alert>{t(error)}</Alert>}
    <div className="account-grid"><section className="surface profile-card"><div className="profile-cover"><span className="profile-cover-lines" /></div>
      <div className="profile-content"><Avatar name={user.displayName} large /><StatusBadge enabled={user.enabled} /><h2>{user.displayName}</h2><p className="profile-username">@{user.username}</p><RoleBadges roles={user.roles} />
        <div className="profile-details"><div><Mail size={17} /><span>{t("邮箱地址")}</span><strong>{user.email}</strong></div><div><Fingerprint size={17} /><span>{t("用户编号")}</span><strong>#{user.id.toString().padStart(4, '0')}</strong></div></div></div>
    </section><div className="account-right"><section className="surface account-section"><div className="section-title"><ShieldCheck size={20} /><h2>{t("我的角色")}</h2></div><p className="section-description">{t("角色由管理员分配，决定你可以访问的功能。")}</p>
      <div className="capability-list">{user.roles.map(role => <div className="capability" key={role}><span className={`capability-icon role-${role.toLowerCase()}`}><UserRound size={19} /></span><div><strong>{t(ROLE_LABELS[role])}</strong><p>{t(ROLE_DESCRIPTIONS[role])}</p></div><span className="capability-check">{t("已分配")}</span></div>)}</div>
      {user.roles.includes('ADMINISTRATOR') && <Link className="text-link" to="/users">{t("前往用户管理")}<ArrowUpRight size={16} /></Link>}</section>
      <section className="surface account-section"><div className="section-title"><CalendarDays size={20} /><h2>{t("账户记录")}</h2></div><dl className="account-dates"><div><dt>{t("创建时间")}</dt><dd>{formatDate(user.createdAt, true, locale)}</dd></div><div><dt>{t("最近登录")}</dt><dd>{formatDate(user.lastLoginAt, true, locale)}</dd></div><div><dt>{t("资料更新")}</dt><dd>{formatDate(user.updatedAt, true, locale)}</dd></div></dl></section></div></div>
  </div>
}
