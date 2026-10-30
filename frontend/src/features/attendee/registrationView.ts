export { useAttendeeResource as useRegistrations } from './useAttendeeResource'

export interface RegistrationDetails {
  registrationId: string
  reference: string
  registeredAt: string
  /** Older servers omit this; null means no recorded withdrawal date. */
  withdrawnAt?: string | null
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
