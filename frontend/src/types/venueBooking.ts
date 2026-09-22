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
}
