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
  conflicts: { eventName: string; startDatetime: string; endDatetime: string }[]
  reasons: string[]
}

export type VenueBookingStatus = 'pending' | 'confirmed' | 'changed' | 'rejected' | 'cancelled'

export interface EventVenueBookingDto {
  bookingId: string
  status: VenueBookingStatus
  venueId: string
  venueAddress: string | null
  venueCapacity: number | null
  bookingNotes: string | null
  suitabilityNote: string | null
  rejectReason: string | null
  submittedAt: string | null
  submittedBy: string | null
}

export const BOOKING_STATUS_LABELS: Record<VenueBookingStatus, string> = {
  pending: 'Pending venue review',
  confirmed: 'Confirmed',
  changed: 'Changed',
  rejected: 'Rejected',
  cancelled: 'Cancelled',
}
