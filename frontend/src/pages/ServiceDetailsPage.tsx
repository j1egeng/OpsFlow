import { ArrowLeft, Boxes, Pencil, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ServiceEditor, ServiceStatusDialog } from '../components/ServiceDialogs'
import { Alert, formatDate, Spinner, StatusBadge, Toast } from '../components/ui'
import { useI18n } from '../i18n/I18nContext'
import { api, errorMessage } from '../lib/api'
import type { SoftwareService } from '../types'

export function ServiceDetailsPage() {
  const { id } = useParams()
  const { t, locale } = useI18n()
  const { user } = useAuth()
  const admin = !!user?.roles.includes('ADMINISTRATOR')
  const location = useLocation()
  const [service, setService] = useState<SoftwareService | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [modal, setModal] = useState<'edit' | 'status' | null>(null)
  const [toast, setToast] = useState(location.state?.notice === '服务已创建。' ? '服务已创建。' : '')
  const closeToast = useCallback(() => setToast(''), [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setService(null); setModal(null)
    if (!id || !/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))) {
      setError('软件服务不存在'); setLoading(false); return
    }
    api.service(Number(id), controller.signal).then(setService).catch((failure: unknown) => {
      if (!controller.signal.aborted) setError(errorMessage(failure))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [id, revision])

  function saved(updated: SoftwareService) {
    setToast(modal === 'status' ? updated.enabled ? '服务已启用。' : '服务已停用。' : '服务资料已更新。')
    setService(updated); setModal(null)
  }

  return <div className="page-enter"><Link className="text-link back-link" to="/services"><ArrowLeft size={16} />{t('返回服务目录')}</Link>
    <div className="page-heading"><div><span className="eyebrow">SERVICE DETAILS</span><h1>{t('服务详情')}</h1><p>{service?.name}</p></div>
      <button className="button button-secondary" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={16} className={loading ? 'spin' : ''} />{t('刷新信息')}</button></div>
    {error ? <Alert>{t(error)}</Alert> : loading ? <div className="surface table-loading"><Spinner label="正在加载详情" /></div> : service && <section className="surface service-detail-card">
      <div className="service-detail-heading"><span className="stat-icon stat-icon-green"><Boxes size={24} /></span><div><h2>{service.name}</h2><code>{service.code}</code></div><StatusBadge enabled={service.enabled} /></div>
      <div className="service-description"><h3>{t('服务描述')}</h3><p>{service.description || t('暂无描述')}</p></div>
      <Link className="text-link" to={`/incidents?serviceId=${service.id}`}>{t('查看关联事件')}</Link>
      <Link className="text-link change-source-link" to={`/changes?serviceId=${service.id}`}>{t('查看关联变更')}</Link>
      <dl className="service-facts"><div><dt>{t('负责人')}</dt><dd>{service.owner ? <>{service.owner.displayName} (@{service.owner.username}){!service.owner.enabled && <StatusBadge enabled={false} />}</> : t('未分配')}</dd></div>
        <div><dt>{t('创建人编号')}</dt><dd>#{service.createdBy}</dd></div><div><dt>{t('创建时间')}</dt><dd>{formatDate(service.createdAt, true, locale)}</dd></div>
        <div><dt>{t('更新时间')}</dt><dd>{formatDate(service.updatedAt, true, locale)}</dd></div><div><dt>{t('版本')}</dt><dd>{service.version}</dd></div></dl>
      {admin && <div className="service-detail-actions"><button className="button button-primary" onClick={() => setModal('edit')}><Pencil size={16} />{t('编辑服务')}</button>
        <button className="button button-secondary" onClick={() => setModal('status')}>{t(service.enabled ? '停用服务' : '启用服务')}</button></div>}
    </section>}
    {admin && service && modal === 'edit' && <ServiceEditor service={service} onSaved={saved} onClose={() => setModal(null)} />}
    {admin && service && modal === 'status' && <ServiceStatusDialog service={service} onSaved={saved} onClose={() => setModal(null)} />}
    {toast && <Toast message={toast} onClose={closeToast} />}
  </div>
}
