import { useEffect, useState } from 'react'

/** Refresh related read models without navigation or discarding form state. */
export function useLiveRefresh(enabled = true, intervalMs = 5000, changeEvent?: string) {
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    if (!enabled) return
    const refresh = () => setRevision(value => value + 1)
    const timer = window.setInterval(refresh, intervalMs)
    window.addEventListener('focus', refresh)
    if (changeEvent) window.addEventListener(changeEvent, refresh)
    return () => {
      window.clearInterval(timer); window.removeEventListener('focus', refresh)
      if (changeEvent) window.removeEventListener(changeEvent, refresh)
    }
  }, [enabled, intervalMs, changeEvent])
  return revision
}
