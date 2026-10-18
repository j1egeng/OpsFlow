import { useEffect, useState } from 'react'
import type { PageResult } from '../types'
import { errorMessage } from './api'

export function usePagedActivity<T>(loader: (id: number, page: number, size: number, signal?: AbortSignal) => Promise<PageResult<T>>,
  id: number, page: number, revision: number, enabled: boolean) {
  const [data, setData] = useState<PageResult<T> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!enabled) { setData(null); setLoading(false); return }
    const controller = new AbortController()
    setLoading(true); setError('')
    loader(id, page, 10, controller.signal).then(result => { if (!controller.signal.aborted) setData(result) })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [loader, id, page, revision, enabled])
  return { data, loading, error }
}
