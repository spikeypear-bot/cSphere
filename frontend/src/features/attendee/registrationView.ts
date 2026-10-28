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

export const REGISTRATION_LABELS = { confirmed: 'Confirmed', withdrawn: 'Withdrawn', event_cancelled: 'Cancelled by event cancellation' }
export const EVENT_LABELS = { confirmed: 'Confirmed', reconfirming: 'Awaiting re-confirmation', cancelled: 'Cancelled', completed: 'Completed' }

export function registrationDateTime(value: string): string {
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'long', timeStyle: 'short', timeZone: 'Asia/Singapore',
  }).format(new Date(value)) + ' (SGT)'
}

/** Fresh on navigation, focus and the existing live-refresh interval; old responses cannot win. */
export function useRegistrations<T>(path: string) {
  const [result, setResult] = useState<{ path: string; data?: T; error?: ApiClientError } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const revision = useLiveRefresh()
  useEffect(() => {
    let active = true
    apiClient.get<T>(path, { cache: 'no-store' })
      .then(data => { if (active) setResult({ path, data }) })
      .catch(error => { if (active) setResult({ path, error }) })
    return () => { active = false }
  }, [path, attempt, revision])
  return {
    data: result?.path === path ? result.data : undefined,
    error: result?.path === path ? result.error : undefined,
    retry: () => { setResult(null); setAttempt(value => value + 1) },
  }
}
