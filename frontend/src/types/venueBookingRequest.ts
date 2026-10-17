// Mirrors backend/.../venuebooking/dto/{VenueOptionDto,EventVenueBookingDto}.java (EC03)
import type { VenueDto } from './venue'

export type VenueVerdict = 'suitable' | 'needs_justification' | 'blocked'

export interface VenueOptionDto {
  venue: VenueDto
  verdict: VenueVerdict
  capacityOk: boolean
  /** Capacity minus expected attendance; negative when too small. */
  spareCapacity: number
  missingAccessibility: string[]
  missingFacilities?: string[]
  unavailablePeriods?: { description: string; affectedFrom: string | null; affectedUntil: string | null }[]
  /** ECL-C7: another event's booking, as "Booked by another event" and its period only. */
  conflicts: { description: string; startDatetime: string; endDatetime: string }[]
  reasons: string[]
}

export type VenueBookingStatus = 'pending' | 'approved' | 'changed' | 'rejected' | 'cancelled'

export interface EventVenueBookingDto {
  timing?: import('./bookingTiming').BookingTiming
  bookingId: string
  requiresAlternative?: boolean
  replacesBookingId?: string | null
  status: VenueBookingStatus
  venueId: string
  venueAddress: string | null
  venueCapacity: number | null
  bookingNotes: string | null
  suitabilityNote: string | null
  rejectReason: string | null
  alternativeVenueId?: string | null
  alternativeVenueAddress?: string | null
  alternativeArrangement?: string | null
  submittedAt: string | null
  submittedBy: string | null
  startDatetime?: string | null
  endDatetime?: string | null
}

export const BOOKING_STATUS_LABELS: Record<VenueBookingStatus, string> = {
  pending: 'Pending venue review',
  approved: 'Approved',
  changed: 'Changed',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}
