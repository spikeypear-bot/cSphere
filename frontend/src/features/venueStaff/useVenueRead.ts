import { useEffect, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'

/** Fresh GET on mount/path change; ignore responses from a page already left. */
export function useVenueRead<T>(path: string) {
  const [result, setResult] = useState<{ path: string; data?: T; error?: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    apiClient.get<T>(path).then(data => { if (active) setResult({ path, data }) })
      .catch((error: unknown) => {
        if (active) setResult({ path, error: error instanceof ApiClientError ? error.message : 'Could not load details. Please try again.' })
      })
    return () => { active = false }
  }, [path, attempt])
  const current = result?.path === path ? result : null
  return { data: current?.data, error: current?.error,
    retry: () => { setResult(null); setAttempt(value => value + 1) } }
}
