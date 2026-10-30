import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useLiveRefresh } from '../../lib/useLiveRefresh'
import { ATTENDEE_CHANGED_EVENT } from './attendeeRefresh'

interface Snapshot<T> { path: string; version: number; data?: T; error?: ApiClientError }

/** One ordering for background reads, reconciliation and saved mutations on an attendee resource. */
export function useAttendeeResource<T>(path: string, enabled = true) {
  const [result, setResult] = useState<Snapshot<T> | null>(null)
  const [attempt, setAttempt] = useState(0)
  const sequence = useRef(0)
  const applied = useRef(0)
  const latest = useRef<Snapshot<T> | null>(null)
  const activePath = useRef<string | null>(enabled ? path : null)
  const revision = useLiveRefresh(enabled, 5000, ATTENDEE_CHANGED_EVENT)
  const invalidate = useCallback(() => { applied.current = ++sequence.current }, [])

  useEffect(() => {
    activePath.current = enabled ? path : null
    return () => { activePath.current = null; invalidate() }
  }, [path, enabled, invalidate])

  const read = useCallback(async (reportError: boolean): Promise<T | undefined> => {
    if (activePath.current !== path) return undefined
    const version = ++sequence.current
    const newerValue = () => activePath.current === path && latest.current?.path === path
      && latest.current.version > version && latest.current.version === applied.current ? latest.current.data : undefined
    try {
      const data = await apiClient.get<T>(path, { cache: 'no-store' })
      if (activePath.current !== path || version < applied.current) return newerValue()
      applied.current = version
      const snapshot = { path, version, data }
      latest.current = snapshot
      setResult(snapshot)
      return data
    } catch (error) {
      if (activePath.current !== path || version < applied.current) return newerValue()
      applied.current = version
      if (reportError) {
        const snapshot = { path, version, error: error as ApiClientError }
        latest.current = snapshot
        setResult(snapshot)
      }
      throw error
    }
  }, [path])

  useEffect(() => { void read(true).catch(() => { /* Reported in resource state. */ }) }, [read, attempt, revision])

  return {
    data: enabled && result?.path === path ? result.data : undefined,
    error: enabled && result?.path === path ? result.error : undefined,
    retry: () => {
      invalidate()
      latest.current = null
      setResult(null)
      setAttempt(value => value + 1)
    },
    // Mutations/reconciliation keep a saved view if the follow-up GET itself fails.
    refresh: () => read(false),
    replace: (value: T | ((current: T | undefined) => T)) => {
      if (activePath.current !== path) return false
      const current = latest.current?.path === path ? latest.current.data : undefined
      const data = typeof value === 'function' ? (value as (current: T | undefined) => T)(current) : value
      const snapshot = { path, version: ++sequence.current, data }
      applied.current = snapshot.version
      latest.current = snapshot
      setResult(snapshot)
      return true
    },
  }
}
