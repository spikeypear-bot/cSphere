import type { VenueDto } from './venue'

export interface VenueBookingConflict {
  bookingId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  setupDurationMinutes?: number
  turnaroundDurationMinutes?: number
  effectiveStart?: string
  effectiveEnd?: string
}

export interface VenueUnavailabilityConflict {
  unavailabilityId: string
  reason: string
  startDatetime: string
  endDatetime: string
}

export interface VenueBookingDto {
  timing?: import('./bookingTiming').BookingTiming
  bookingId: string
  requiresAlternative?: boolean
  replacesBookingId?: string | null
  status: 'pending' | 'approved' | 'changed' | 'rejected' | 'cancelled'
  venue: VenueDto
  event: {
    eventId: string
    eventName: string
    startDatetime: string
    endDatetime: string
    expectedAttendance: number
    venueRequirements: string
    accessibilityNeeds: string[]
    equipmentRequirements: string | null
  }
  /** EC03: the coordinator's notes and, if the venue lacks a requested
   * accessibility feature, their justification. Absent on older bookings. */
  bookingNotes?: string | null
  suitabilityNote?: string | null
  rejectReason?: string | null
  alternativeVenueId?: string | null
  alternativeVenueAddress?: string | null
  alternativeArrangement?: string | null
  submittedAt?: string | null
}
