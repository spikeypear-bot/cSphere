import { useEffect, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'

export interface SafetyData<T> {
  data: T | null
  error: string | null
  loading: boolean
  reload: () => void
  setData: (data: T) => void
}

export function useSafetyData<T>(path: string | null): SafetyData<T> {
  const [reloadToken, setReloadToken] = useState(0)
  const requestKey = path ? `${path}:${reloadToken}` : null
  const [result, setResult] = useState<{
    requestKey: string | null
    data: T | null
    error: string | null
  }>({ requestKey: null, data: null, error: null })

  useEffect(() => {
    if (!path || !requestKey) return
    let cancelled = false
    apiClient.get<T>(path)
      .then((result) => {
        if (cancelled) return
        setResult({ requestKey, data: result, error: null })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setResult({
          requestKey,
          data: null,
          error: err instanceof ApiClientError ? err.message : 'Could not load this information.',
        })
      })
    return () => {
      cancelled = true
    }
  }, [path, requestKey])

  const isCurrent = result.requestKey === requestKey
  return {
    data: isCurrent ? result.data : null,
    error: isCurrent ? result.error : null,
    loading: requestKey !== null && !isCurrent,
    reload: () => setReloadToken((token) => token + 1),
    setData: (data) => setResult({ requestKey, data, error: null }),
  }
}
