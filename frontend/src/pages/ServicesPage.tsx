import { Boxes, ChevronLeft, ChevronRight, Eye, Pencil, Plus, RefreshCw, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ServiceEditor, ServiceStatusDialog } from '../components/ServiceDialogs'
import { Alert, formatDate, Spinner, StatusBadge, Toast } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, ApiError, errorMessage } from '../lib/api'
import type { ServicePage, SoftwareService } from '../types'

type Modal = { type: 'create' } | { type: 'edit' | 'status'; service: SoftwareService } | null

export function ServicesPage() {
  const { t, locale } = useI18n()
  const { user, refreshUser } = useAuth()
  const admin = !!user?.roles.includes('ADMINISTRATOR')
  const navigate = useNavigate()
  const [data, setData] = useState<ServicePage | null>(null)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [filter, setFilter] = useState('all')
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [modal, setModal] = useState<Modal>(null)
  const [toast, setToast] = useState('')
  const closeToast = useCallback(() => setToast(''), [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    api.services(page, size, filter === 'all' ? undefined : filter === 'true', controller.signal).then(result => {
      if (page > 0 && !result.items.length) { setPage(Math.max(result.totalPages - 1, 0)); return }
      setData(result)
    }).catch((failure: unknown) => {
      if (!controller.signal.aborted) {
        setError(errorMessage(failure))
        if (failure instanceof ApiError && failure.status === 403) void refreshUser().catch(() => {})
      }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, size, filter, revision, refreshUser])

  function saved(service: SoftwareService, created = false) {
    const statusChanged = modal?.type === 'status'
    setModal(null)
    if (created) { navigate(`/services/${service.id}`, { state: { notice: '服务已创建。' } }); return }
    setToast(statusChanged ? service.enabled ? '服务已启用。' : '服务已停用。' : '服务资料已更新。')
    setRevision(value => value + 1)
  }

  return <div className="page-enter"><div className="page-heading"><div><span className="eyebrow">SERVICE CATALOG</span>
    <h1>{t('服务管理')}<span className="heading-count">{loading ? '—' : data?.totalElements ?? '—'}</span></h1><p>{t('维护软件服务目录，明确负责人并管理服务状态。')}</p></div>
    {admin && <button className="button button-primary" onClick={() => setModal({ type: 'create' })}><Plus size={18} />{t('新建服务')}</button>}</div>
    <section className="surface"><div className="table-heading"><div><h2>{t('服务目录')}</h2><p>{t('统一管理业务系统与软件服务。')}</p></div><span className="subtle-label">SOFTWARE SERVICES</span></div>
      <div className="table-toolbar"><label className="service-filter"><span>{t('筛选状态')}</span><select value={filter} onChange={event => { setFilter(event.target.value); setPage(0) }}>
        <option value="all">{t('全部状态')}</option><option value="true">{t('仅启用')}</option><option value="false">{t('仅停用')}</option></select></label>
        <button className="button button-secondary button-refresh" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />{t('刷新列表')}</button></div>
      {error ? <div className="table-message"><Alert>{t(error)}</Alert><button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>{t('重新加载')}</button></div>
        : loading ? <div className="table-loading"><Spinner label="正在加载服务" /></div>
        : <div className="table-scroll"><table className="users-table services-table" aria-label={t('服务列表')}><thead><tr>
          <th>{t('服务')}</th><th>{t('服务编码')}</th><th>{t('负责人')}</th><th>{t('状态')}</th><th>{t('更新时间')}</th><th>{t('操作')}</th>
        </tr></thead><tbody>{data?.items.map(service => <tr key={service.id}>
          <td><Link className="service-name" to={`/services/${service.id}`}>{service.name}</Link></td><td><code className="service-code">{service.code}</code></td>
          <td><div className="service-owner">{service.owner ? <><strong>{service.owner.displayName}</strong><small>@{service.owner.username}{!service.owner.enabled && ` · ${t('已停用')}`}</small></> : t('未分配')}</div></td>
          <td><StatusBadge enabled={service.enabled} /></td><td className="date-cell">{formatDate(service.updatedAt, false, locale)}</td>
          <td><div className="row-actions"><Link className="row-role-button" to={`/services/${service.id}`} aria-label={t('查看 {name} 的详情', { name: service.code })}><Eye size={15} />{t('详情')}</Link>
            {admin && <><button className="row-role-button" aria-label={t('编辑服务 {name}', { name: service.code })} onClick={() => setModal({ type: 'edit', service })}><Pencil size={14} />{t('编辑')}</button>
              <button role="switch" aria-checked={service.enabled} aria-label={t('服务状态：{name}', { name: service.code })} className={`toggle ${service.enabled ? 'toggle-on' : ''}`} onClick={() => setModal({ type: 'status', service })}><span /></button></>}
          </div></td></tr>)}
          {!data?.items.length && <tr><td colSpan={6}><div className="empty-state"><span><Boxes size={28} /></span><h3>{t(filter === 'all' ? '服务目录还是空的' : '没有符合条件的服务')}</h3>
            <p>{t(admin && filter === 'all' ? '创建第一个服务，为后续事件与变更建立关联。' : '调整状态筛选，或联系管理员添加服务。')}</p></div></td></tr>}
        </tbody></table></div>}
      <div className="table-footer"><div className="page-size"><span>{t('每页')}</span><select aria-label={t('每页服务数')} value={size} disabled={loading} onChange={event => { setSize(Number(event.target.value)); setPage(0) }}>
        <option value={10}>10</option><option value={20}>20</option><option value={50}>50</option></select><span>{t('条，共 {count} 项服务', { count: data?.totalElements ?? 0 })}</span></div>
        <div className="pagination"><span>{t('第 {page} / {total} 页', { page: page + 1, total: Math.max(data?.totalPages || 1, 1) })}</span>
          <button className="icon-button" aria-label={t('上一页')} disabled={loading || !!error || !page} onClick={() => setPage(value => value - 1)}><ChevronLeft size={17} /></button>
          <button className="icon-button" aria-label={t('下一页')} disabled={loading || !!error || !data || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)}><ChevronRight size={17} /></button></div></div>
    </section><div className="access-note"><ShieldCheck size={16} /><span>{t(admin ? '停用不会删除历史记录；服务编码不区分大小写且必须唯一。' : '当前为只读访问。服务创建、修改及启停由管理员操作。')}</span></div>
    {admin && modal?.type === 'create' && <ServiceEditor onClose={() => setModal(null)} onSaved={service => saved(service, true)} />}
    {admin && modal?.type === 'edit' && <ServiceEditor service={modal.service} onClose={() => setModal(null)} onSaved={saved} />}
    {admin && modal?.type === 'status' && <ServiceStatusDialog service={modal.service} onClose={() => setModal(null)} onSaved={saved} />}
    {toast && <Toast message={toast} onClose={closeToast} />}
  </div>
}
