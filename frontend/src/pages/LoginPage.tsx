import { useI18n } from '../i18n/I18nContext'
import { ArrowRight, Eye, EyeOff, Fingerprint, LockKeyhole, ShieldCheck, Users } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { errorMessage } from '../lib/api'
import { Alert, Brand } from '../components/ui'
import { ConnectionStatus } from '../components/ConnectionStatus'
import { LanguageSwitcher } from '../components/LanguageSwitcher'

function loginDestination(from: string | undefined, administrator: boolean) {
  const safe = from === '/account' || from === '/audit-logs' || from === '/dashboard' || from === '/changes' || from === '/services' || from === '/incidents' || /^\/(services|incidents|changes)\/[1-9]\d*$/.test(from || '')
  return safe ? from! : administrator ? '/users' : '/account'
}

export function LoginPage() {
  const { t } = useI18n()
  const { user, login, notice } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from
  if (user) return <Navigate to={loginDestination(from, user.roles.includes('ADMINISTRATOR'))} replace />

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try {
      const current = await login(username, password)
      setPassword('')
      navigate(loginDestination(from, current.roles.includes('ADMINISTRATOR')), { replace: true })
    } catch (failure) { setError(errorMessage(failure)) }
    finally { setBusy(false) }
  }

  return <div className="login-page"><section className="login-story">
    <Brand light /><div className="story-content"><span className="eyebrow light-eyebrow">TEAM OPERATIONS, CONNECTED.</span>
      <h1>{t("团队的事件与变更，")}<br />{t("集中在一个工作台")}<span>{t("。")}</span></h1>
      <p>{t("连接团队成员与工作流程，")}<br />{t("为每一次操作保留清晰的记录。")}</p>
      <div className="identity-illustration" aria-hidden="true"><div className="identity-orbit orbit-one" /><div className="identity-orbit orbit-two" />
        <span className="identity-node node-one"><Users size={22} /></span><span className="identity-node node-two"><LockKeyhole size={22} /></span>
        <div className="identity-center"><Fingerprint size={46} strokeWidth={1.3} /></div>
        <span className="identity-caption"><ShieldCheck size={15} />{t("身份与权限，由此开始")}</span></div>
    </div><div className="story-footer"><span>OpsFlow Project</span><span>Team 13 / SWE5006</span></div>
  </section><section className="login-panel"><div className="login-topline"><span className="login-mobile-brand"><Brand /></span><div className="login-topbar-actions"><LanguageSwitcher /><ConnectionStatus /></div></div>
    <div className="login-form-wrap"><span className="eyebrow">YOUR WORKSPACE</span><h2>{t("欢迎回来")}</h2><p className="login-description">{t("登录 OpsFlow，进入你的团队工作台。")}</p>
      {notice && !error && <Alert info>{t(notice)}</Alert>}{error && <Alert>{t(error)}</Alert>}
      <form onSubmit={event => void submit(event)} className="login-form">
        <label className="field"><span>{t("用户名")}</span><div className="input-with-icon"><Users size={18} /><input autoComplete="username" name="username" placeholder={t("输入你的用户名")} value={username} onChange={event => setUsername(event.target.value)} maxLength={50} required disabled={busy} /></div></label>
        <label className="field"><span>{t("密码")}</span><div className="input-with-icon"><LockKeyhole size={18} /><input autoComplete="current-password" name="password" placeholder={t("输入你的密码")} type={visible ? 'text' : 'password'} value={password} onChange={event => setPassword(event.target.value)} maxLength={72} required disabled={busy} />
          <button type="button" className="password-toggle" aria-label={visible ? t("隐藏密码") : t("显示密码")} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button></div></label>
        <button className="button button-primary login-submit" type="submit" disabled={busy}>{busy ? t("正在登录…") : t("登录工作台")}{!busy && <ArrowRight size={18} />}</button>
      </form>
      <div className="login-help"><ShieldCheck size={17} /><p>{t("账号由团队管理员创建。")}<br />{t("如需开通账号或恢复访问，请联系管理员。")}</p></div>
    </div><footer className="login-panel-footer"><span>{t("安全登录 · 角色访问控制")}</span><span>© {new Date().getFullYear()} OpsFlow</span></footer>
  </section></div>
}
