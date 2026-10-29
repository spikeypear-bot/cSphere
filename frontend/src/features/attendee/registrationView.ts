import { useEffect, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useLiveRefresh } from '../../lib/useLiveRefresh'

export interface RegistrationDetails {
  registrationId: string
  reference: string
  registeredAt: string
  registrationStatus: 'confirmed' | 'withdrawn' | 'event_cancelled'
  eventId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  eventStatus: 'confirmed' | 'reconfirming' | 'cancelled' | 'completed'
  eventDetailsAvailable: boolean
  withdrawalAvailable: boolean
  withdrawalReason: string
}


export function registrationDateTime(value: string): string {
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'long', timeStyle: 'short', timeZone: 'Asia/Singapore',
  }).format(new Date(value)) + ' (SGT)'
}

/** Fresh on navigation, focus and the existing live-refresh interval; old responses cannot win. */
export function useRegistrations<T>(path: string, enabled = true) {
  const [result, setResult] = useState<{ path: string; data?: T; error?: ApiClientError } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const revision = useLiveRefresh(enabled)
  useEffect(() => {
    if (!enabled) return
    let active = true
    apiClient.get<T>(path, { cache: 'no-store' })
      .then(data => { if (active) setResult({ path, data }) })
      .catch(error => { if (active) setResult({ path, error }) })
    return () => { active = false }
  }, [path, attempt, revision, enabled])
  return {
    data: result?.path === path ? result.data : undefined,
    error: result?.path === path ? result.error : undefined,
    retry: () => { setResult(null); setAttempt(value => value + 1) },
  }
}
