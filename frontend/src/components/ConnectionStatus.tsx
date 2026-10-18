import { useCallback, useEffect, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { api } from '../lib/api'
import { useI18n } from '../i18n/I18nContext'

export function ConnectionStatus() {
  const { t } = useI18n()
  const [state, setState] = useState<'checking' | 'online' | 'offline'>('checking')
  const check = useCallback(async () => {
    setState('checking')
    try { const response = await api.health(); setState(response.status === 'UP' ? 'online' : 'offline') }
    catch { setState('offline') }
  }, [])
  useEffect(() => { void check(); const interval = setInterval(() => void check(), 30_000); return () => clearInterval(interval) }, [check])
  return <div className={`connection-status connection-${state}`}><i />
    {t(state === 'online' ? '服务已连接' : state === 'checking' ? '正在连接服务' : '服务暂不可用')}
    {state === 'offline' && <button className="icon-button" type="button" onClick={() => void check()} aria-label={t('重试服务连接')}><RefreshCw size={13} /></button>}
  </div>
}
