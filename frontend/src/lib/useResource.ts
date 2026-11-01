import { useEffect, useEffectEvent, useState } from 'react'

export interface Resource<T> {
  data: T | null
  error: string | null
  loading: boolean
  /** Load again (the Try again button), keeping nothing from the failed try. */
  retry: () => void
}

/** Loads something for the console and tracks loading / error / data.
 * `key` names what is being loaded (e.g. the date window): when it changes
 * the load runs again and the previous answer is no longer shown. */
export function useResource<T>(load: () => Promise<T>, key: string): Resource<T> {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{ key: string; data?: T; error?: string } | null>(null)
  const requestKey = `${key}#${attempt}`
  const run = useEffectEvent(load)

  useEffect(() => {
    let cancelled = false
    run()
      .then((data) => { if (!cancelled) setResult({ key: requestKey, data }) })
      .catch((err: unknown) => {
        // The server's message (ApiClientError, or an api module's own error).
        if (!cancelled) {
          setResult({ key: requestKey, error: err instanceof Error && err.message ? err.message : 'Something went wrong.' })
        }
      })
    return () => { cancelled = true }
  }, [requestKey])

  const current = result?.key === requestKey ? result : null
  return {
    data: current && current.error === undefined ? (current.data as T) : null,
    error: current?.error ?? null,
    loading: current === null,
    retry: () => setAttempt((n) => n + 1),
  }
}
