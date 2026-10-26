import type { VenueDto } from './venue'
import type { VenueBookingStatus } from './venueBookingStatus'

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
  currentCoordinatorName?: string | null
  currentCoordinatorEmail?: string | null
  effectiveRequirements?: import('./venueBookingRequest').BookingRequirements
  relatedBookings?: { bookingId: string; venueId: string; venueAddress: string; status: VenueBookingStatus;
    startDatetime: string; endDatetime: string; effectiveStart: string; effectiveEnd: string }[]
  requirements?: import('./venueBookingRequest').BookingRequirements | null
  holds?: import('./venueHold').VenueHold[]
  timing?: import('./bookingTiming').BookingTiming
  bookingId: string
  requiresAlternative?: boolean
  replacesBookingId?: string | null
  status: VenueBookingStatus
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
