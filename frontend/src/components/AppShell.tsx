import { ChevronRight, Fingerprint, LogOut, Menu, ShieldCheck, Users, X, Boxes, AlertTriangle, ArrowRightLeft, LayoutDashboard, FileSearch } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Avatar, Brand } from './ui'
import { ConnectionStatus } from './ConnectionStatus'
import { LanguageSwitcher } from './LanguageSwitcher'
import { useI18n } from '../i18n/I18nContext'

export function AppShell() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const { t } = useI18n()
  if (!user) return null
  const admin = user.roles.includes('ADMINISTRATOR')
  return <div className="app-shell">
    {menuOpen && <button className="mobile-overlay" aria-label={t('关闭导航菜单')} onClick={() => setMenuOpen(false)} />}
    <aside className={`sidebar ${menuOpen ? 'sidebar-open' : ''}`}>
      <div className="sidebar-brand"><Brand light /><button className="icon-button mobile-only" aria-label={t('关闭导航')} onClick={() => setMenuOpen(false)}><X /></button></div>
      <div className="workspace-label"><span className="workspace-avatar">13</span><div><strong>Team 13</strong><span>{t('项目工作空间')}</span></div><span className="workspace-dot" /></div>
      <p className="nav-section-label">{t('工作台')}</p>
      <nav aria-label={t('主导航')}>
        <NavLink to="/dashboard" onClick={() => setMenuOpen(false)}><LayoutDashboard size={19} /><span>{t('运营总览')}</span></NavLink>
        <NavLink to="/account" onClick={() => setMenuOpen(false)}><Fingerprint size={19} /><span>{t('我的账户')}</span></NavLink>
        {admin && <NavLink to="/users" onClick={() => setMenuOpen(false)}><Users size={19} /><span>{t('用户管理')}</span><span className="nav-tag">ADMIN</span></NavLink>}
        <NavLink to="/services" onClick={() => setMenuOpen(false)}><Boxes size={19} /><span>{t('服务管理')}</span></NavLink>
        <NavLink to="/incidents" onClick={() => setMenuOpen(false)}><AlertTriangle size={19} /><span>{t('事件管理')}</span></NavLink>
        <NavLink to="/changes" onClick={() => setMenuOpen(false)}><ArrowRightLeft size={19} /><span>{t('变更管理')}</span></NavLink>
        {admin && <NavLink to="/audit-logs" onClick={() => setMenuOpen(false)}><FileSearch size={19} /><span>{t('审计日志')}</span><span className="nav-tag" aria-hidden="true">ADMIN</span></NavLink>}
      </nav>
      <div className="sidebar-bottom"><ShieldCheck size={20} /><p><strong>{t('身份与访问管理')}</strong><span>{t('清晰的角色，有序的协作。')}</span></p></div>
      <div className="sidebar-user"><Avatar name={user.displayName} /><div><strong>{user.displayName}</strong><span>@{user.username}</span></div>
        <button className="icon-button" aria-label={t('退出登录')} title={t('退出登录')} onClick={() => logout()}><LogOut size={18} /></button></div>
    </aside>
    <div className="workspace-main"><header className="topbar">
      <div className="breadcrumbs"><button className="icon-button mobile-only" aria-label={t('打开导航')} onClick={() => setMenuOpen(true)}><Menu size={21} /></button>
        <span>{t('工作台')}</span><ChevronRight size={14} /><strong>{t(location.pathname === '/audit-logs' ? '审计日志' : location.pathname.startsWith('/changes') ? '变更管理' : location.pathname === '/dashboard' ? '运营总览' : location.pathname.startsWith('/incidents') ? '事件管理' : location.pathname.startsWith('/services') ? '服务管理' : location.pathname === '/users' ? '用户管理' : '我的账户')}</strong></div>
      <div className="topbar-right"><LanguageSwitcher /><ConnectionStatus /><span className="topbar-divider" /><NavLink className="topbar-avatar" to="/account" aria-label={t('查看我的账户')}><Avatar name={user.displayName} /></NavLink></div>
    </header>
    <main className="page-main"><Outlet /></main>
    <footer className="workspace-footer"><span>OpsFlow · Team 13</span><span>{t('事件与变更管理平台')}</span></footer></div>
  </div>
}
