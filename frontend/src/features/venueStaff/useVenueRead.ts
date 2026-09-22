import { useEffect, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'

/** Fresh GET on mount/path change; ignore responses from a page already left. */
export function useVenueRead<T>(path: string, retainPreviousData = false) {
  const [result, setResult] = useState<{ path: string; attempt: number; data?: T; error?: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    apiClient.get<T>(path, { cache: 'no-store' }).then(data => { if (active) setResult({ path, attempt, data }) })
      .catch((error: unknown) => {
        if (active) setResult(previous => ({ path, attempt, data: retainPreviousData ? previous?.data : undefined, error: error instanceof ApiClientError ? error.message : 'Could not load details. Please try again.' }))
      })
    return () => { active = false }
  }, [path, attempt, retainPreviousData])
  const current = result?.path === path && result.attempt === attempt ? result : null
  return { data: current?.data ?? (retainPreviousData ? result?.data : undefined), error: current?.error,
    loading: !current,
    retry: () => { setAttempt(value => value + 1) } }
}
