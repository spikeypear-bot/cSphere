import type { VenueDto } from './venue'

export interface VenueBookingDto {
  bookingId: string
  status: 'pending' | 'confirmed' | 'changed' | 'rejected' | 'cancelled'
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
  submittedAt?: string | null
}
