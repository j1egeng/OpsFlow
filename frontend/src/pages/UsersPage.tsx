import { useI18n } from '../i18n/I18nContext'
import { ChevronLeft, ChevronRight, Plus, RefreshCw, Search, ShieldCheck, UserCheck, Users } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { CreateUserDialog, RolesDialog, StatusDialog } from '../components/UserDialogs'
import { Alert, Avatar, formatDate, RoleBadges, Spinner, StatusBadge, Toast } from '../components/ui'
import { api, ApiError, errorMessage } from '../lib/api'
import type { User, UserPage } from '../types'

type Modal = { type: 'create' } | { type: 'roles' | 'status'; user: User } | null

export function UsersPage() {
  const { t, locale } = useI18n()
  const { user: currentUser, refreshUser, logout } = useAuth()
  const [data, setData] = useState<UserPage | null>(null)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [modal, setModal] = useState<Modal>(null)
  const [toast, setToast] = useState('')
  const closeToast = useCallback(() => setToast(''), [])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.users(page, size, controller.signal).then(setData).catch((failure: unknown) => {
      if (!controller.signal.aborted) {
        setError(errorMessage(failure))
        if (failure instanceof ApiError && failure.status === 403) void refreshUser().catch(() => {})
      }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, size, revision, refreshUser])

  const filtered = useMemo(() => {
    const query = search.trim().toLocaleLowerCase()
    return (data?.items || []).filter(member => !query || [member.username, member.displayName, member.email].some(value => value.toLocaleLowerCase().includes(query)))
  }, [data, search])

  function saved(member: User, message: string, created = false) {
    setModal(null); setToast(message)
    if (created) { setPage(0); setSearch('') }
    if (member.id === currentUser?.id) {
      if (!member.enabled) { logout('你的账号已停用，请联系管理员。'); return }
      void refreshUser().catch(() => {})
    }
    setRevision(previous => previous + 1)
  }

  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">IDENTITY & ACCESS</span><h1>{t("用户管理")}<span className="heading-count">{data?.totalElements ?? '—'}</span></h1><p>{t("管理团队成员与访问权限，让合适的人处理合适的工作。")}</p></div>
    <button className="button button-primary" onClick={() => setModal({ type: 'create' })}><Plus size={18} />{t("新建用户")}</button></div>
    <div className="stats-grid"><div className="stat-card"><div><span>{t("团队用户")}</span><strong>{data?.totalElements ?? '—'}<small>{t("位成员")}</small></strong></div><span className="stat-icon stat-icon-green"><Users size={22} /></span></div>
      <div className="stat-card"><div><span>{t("本页启用")}</span><strong>{data ? data.items.filter(member => member.enabled).length : '—'}<small>{t("个账号")}</small></strong></div><span className="stat-icon stat-icon-blue"><UserCheck size={22} /></span></div>
      <div className="stat-card"><div><span>{t("本页管理员")}</span><strong>{data ? data.items.filter(member => member.roles.includes('ADMINISTRATOR')).length : '—'}<small>{t("位成员")}</small></strong></div><span className="stat-icon stat-icon-amber"><ShieldCheck size={22} /></span></div></div>
    <section className="surface users-surface"><div className="table-heading"><div><h2>{t("团队成员")}</h2><p>{t("用户账号、角色及当前状态。")}</p></div><span className="subtle-label">TEAM DIRECTORY</span></div>
      <div className="table-toolbar"><div className="search-field"><Search size={17} /><input aria-label={t("搜索本页用户")} placeholder={t("搜索本页的姓名、用户名或邮箱…")} value={search} onChange={event => setSearch(event.target.value)} /></div>
        <button className="button button-secondary button-refresh" disabled={loading} onClick={() => setRevision(previous => previous + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />{t("刷新列表")}</button></div>
      {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(previous => previous + 1)}>{t("重新加载")}</button></div>
        : loading ? <div className="table-loading"><Spinner label={t("正在加载团队成员")} /></div>
        : <div className="table-scroll"><table className="users-table" aria-label={t("用户列表")}><thead><tr><th>{t("成员")}</th><th>{t("邮箱地址")}</th><th>{t("账户角色")}</th><th>{t("状态")}</th><th>{t("创建时间")}</th><th>{t("操作")}</th></tr></thead><tbody>
          {filtered.map(member => <tr key={member.id}><td><div className="member-cell"><Avatar name={member.displayName} /><div><strong>{member.displayName}{member.id === currentUser?.id && <span className="you-label">{t("你")}</span>}</strong><span>@{member.username}</span></div></div></td>
            <td className="email-cell">{member.email}</td><td><RoleBadges roles={member.roles} /></td><td><StatusBadge enabled={member.enabled} /></td><td className="date-cell">{formatDate(member.createdAt, false, locale)}</td>
            <td><div className="row-actions"><button className="row-role-button" onClick={() => setModal({ type: 'roles', user: member })} aria-label={t('编辑 {name} 的角色', { name: member.username })}><ShieldCheck size={15} />{t("角色")}</button>
              <button type="button" role="switch" aria-checked={member.enabled} aria-label={t('启用状态：{name}', { name: member.username })} className={`toggle ${member.enabled ? 'toggle-on' : ''}`} onClick={() => setModal({ type: 'status', user: member })}><span /></button></div></td></tr>)}
          {!filtered.length && <tr><td colSpan={6}><div className="empty-state"><span><Users size={28} /></span><h3>{search ? t("没有找到匹配的成员") : t("团队成员将在这里显示")}</h3><p>{search ? t("当前搜索仅作用于本页，试试其他关键词或切换分页。") : t("新建一个账号，开始管理团队的访问权限。")}</p>{search && <button className="text-link" onClick={() => setSearch('')}>{t("清除搜索")}</button>}</div></td></tr>}
        </tbody></table></div>}
      <div className="table-footer"><div className="page-size"><span>{t("每页")}</span><select aria-label={t("每页用户数")} value={size} onChange={event => { setSize(Number(event.target.value)); setPage(0); setSearch('') }} disabled={loading}><option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select><span>{t('条，共 {count} 位用户', { count: data?.totalElements ?? 0 })}</span></div>
        <div className="pagination"><span>{t('第 {page} / {total} 页', { page: page + 1, total: Math.max(data?.totalPages || 1, 1) })}</span><button className="icon-button" aria-label={t("上一页")} disabled={loading || page === 0} onClick={() => { setPage(page - 1); setSearch('') }}><ChevronLeft size={17} /></button><button className="icon-button" aria-label={t("下一页")} disabled={loading || !data || page + 1 >= data.totalPages} onClick={() => { setPage(page + 1); setSearch('') }}><ChevronRight size={17} /></button></div></div>
    </section><div className="access-note"><ShieldCheck size={16} /><span>{t("权限变更在下一次请求时生效。系统会保留至少一个启用的管理员账号。")}</span></div>
    {modal?.type === 'create' && <CreateUserDialog onClose={() => setModal(null)} onSaved={member => saved(member, '用户已创建，可以使用新账号登录。', true)} />}
    {modal?.type === 'roles' && <RolesDialog user={modal.user} onClose={() => setModal(null)} onSaved={member => saved(member, '账户角色已更新。')} />}
    {modal?.type === 'status' && <StatusDialog user={modal.user} self={modal.user.id === currentUser?.id} onClose={() => setModal(null)} onSaved={member => saved(member, member.enabled ? '账号已启用。' : '账号已停用。')} />}
    {toast && <Toast message={toast} onClose={closeToast} />}
  </div>
}
