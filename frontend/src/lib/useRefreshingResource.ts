import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react'
import { useLiveRefresh } from './useLiveRefresh'

/** Background reads retain the saved view and never overwrite a newer read or mutation.
 * A slow read can finish across polling ticks; starting another read does not invalidate it. */
export function useRefreshingResource<T>(load: () => Promise<T>, key: string | null, enabled = true, refreshKey: unknown = 0) {
  const revision = useLiveRefresh(enabled && key !== null)
  const [attempt, setAttempt] = useState(0)
  const [snapshot, setSnapshot] = useState<{ key: string; data?: T; error?: string; cause?: unknown } | null>(null)
  const order = useRef(0)
  const applied = useRef(0)
  const active = useRef<string | null>(null)
  const run = useEffectEvent(load)
  const invalidate = useCallback(() => { applied.current = ++order.current }, [])

  useEffect(() => {
    active.current = key
    return () => { active.current = null; invalidate() }
  }, [key, invalidate])

  useEffect(() => { invalidate() }, [refreshKey, invalidate])

  useEffect(() => {
    if (key === null) return
    const version = ++order.current
    void run().then(data => {
      if (active.current !== key || version < applied.current) return
      applied.current = version
      setSnapshot({ key, data })
    }).catch((error: unknown) => {
      if (active.current !== key || version < applied.current) return
      applied.current = version
      setSnapshot(previous => ({ key, data: previous?.key === key ? previous.data : undefined,
        error: error instanceof Error ? error.message : 'Could not load this information.', cause: error }))
    })
  }, [key, attempt, revision, refreshKey])

  const current = snapshot?.key === key ? snapshot : null
  return {
    data: current?.data ?? null,
    error: current?.error ?? null,
    cause: current?.cause,
    loading: key !== null && !current,
    retry: () => { applied.current = ++order.current; setAttempt(value => value + 1) },
    setData: (data: T | ((current: T | null) => T)) => {
      if (key === null || active.current !== key) return
      applied.current = ++order.current
      setSnapshot(previous => ({ key, data: typeof data === 'function'
        ? (data as (current: T | null) => T)(previous?.key === key ? previous.data ?? null : null) : data }))
    },
  }
}
