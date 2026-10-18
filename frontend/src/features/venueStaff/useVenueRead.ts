import { useEffect, useRef, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'

/** Fresh GET on mount/path change; ignore responses from a page already left. */
export function useVenueRead<T>(path: string, retainPreviousData = false, refreshIntervalMs = 0, retainOnRefresh = false) {
  const requestVersion = useRef(0)
  const [result, setResult] = useState<{ path: string; attempt: number; data?: T; error?: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const refresh = () => setAttempt(value => value + 1)
    window.addEventListener('focus', refresh)
    return () => window.removeEventListener('focus', refresh)
  }, [])
  useEffect(() => {
    let active = true
    const read = () => {
      const version = ++requestVersion.current
      apiClient.get<T>(path, { cache: 'no-store' }).then(data => {
        if (active && version === requestVersion.current) setResult({ path, attempt, data })
      }).catch((error: unknown) => {
        if (active && version === requestVersion.current) setResult(previous => ({ path, attempt, data: (retainOnRefresh && previous?.path === path) || retainPreviousData ? previous?.data : undefined, error: error instanceof ApiClientError ? error.message : 'Could not load details. Please try again.' }))
      })
    }
    read()
    const timer = refreshIntervalMs > 0 ? setInterval(read, refreshIntervalMs) : undefined
    return () => { active = false; clearInterval(timer) }
  }, [path, attempt, retainPreviousData, refreshIntervalMs, retainOnRefresh])
  const current = result?.path === path && result.attempt === attempt ? result : null
  // A same-page refresh must not unmount the document: collapsing its height
  // clamps the browser's scroll position and discards open forms/focused fields.
  const samePageData = retainOnRefresh && result?.path === path ? result.data : undefined
  return { data: current?.data ?? samePageData ?? (retainPreviousData ? result?.data : undefined), error: current?.error,
    loading: !current && samePageData === undefined,
    failRead: (message: string) => { setResult({ path, attempt, error: message }) },
    replaceData: (data: T) => { requestVersion.current++; setResult({ path, attempt, data }) },
    retry: () => { setAttempt(value => value + 1) } }
}
