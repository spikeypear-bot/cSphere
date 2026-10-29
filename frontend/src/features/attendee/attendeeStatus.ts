export type AttendeeEventStatus = 'confirmed' | 'reconfirming' | 'cancelled' | 'completed'
export type RegistrationStatus = 'confirmed' | 'withdrawn' | 'event_cancelled'
export type BadgeTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'
export interface Badge { label: string; tone: BadgeTone }
export interface AttendeeStatusInput {
  eventStatus: AttendeeEventStatus
  registrationStatus?: RegistrationStatus | null
  registrationRequired?: boolean | null
  registrationOpensAt?: string | null
  registrationClosesAt?: string | null
  registrationCapacity?: number | null
  availablePlaces?: number | null
}
export const EVENT_BADGES: Record<AttendeeEventStatus, Badge> = {
  confirmed: { label: 'Confirmed', tone: 'success' },
  reconfirming: { label: 'Awaiting Re-confirmation', tone: 'warning' },
  cancelled: { label: 'Cancelled', tone: 'danger' },
  completed: { label: 'Past Event', tone: 'neutral' },
}

/** Display mapping of server data only; the registration command remains authoritative. */
export function registrationBadge(value: AttendeeStatusInput, now = Date.now()): Badge {
  if (value.registrationStatus === 'withdrawn') return { label: 'Withdrawn', tone: 'neutral' }
  if (value.registrationStatus === 'event_cancelled' || (value.registrationStatus && value.eventStatus === 'cancelled'))
    return { label: 'Affected by Cancellation', tone: 'danger' }
  if (value.registrationStatus === 'confirmed') return { label: 'Registration Confirmed', tone: 'success' }
  if (value.eventStatus === 'cancelled') return { label: 'Registration Unavailable', tone: 'neutral' }
  if (value.eventStatus === 'completed') return { label: 'Registration Closed', tone: 'neutral' }
  if (value.eventStatus === 'reconfirming') return { label: 'Registration Paused', tone: 'warning' }
  if (value.registrationRequired === false) return { label: 'Registration Not Required', tone: 'neutral' }
  const opens = Date.parse(value.registrationOpensAt ?? '')
  const closes = Date.parse(value.registrationClosesAt ?? '')
  if (value.registrationRequired !== true || !Number.isFinite(opens) || !Number.isFinite(closes)
    || closes <= opens || value.registrationCapacity == null || value.registrationCapacity < 1 || value.availablePlaces == null)
    return { label: 'Registration Unavailable', tone: 'neutral' }
  if (now < opens) return { label: 'Not Yet Open', tone: 'info' }
  if (now >= closes) return { label: 'Registration Closed', tone: 'neutral' }
  if (value.availablePlaces <= 0) return { label: 'Full', tone: 'danger' }
  return { label: 'Open for Registration', tone: 'success' }
}
