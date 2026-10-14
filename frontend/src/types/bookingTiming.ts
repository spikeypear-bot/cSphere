import type { VenueBookingConflict } from './venueBooking'

export interface BookingTiming {
  setupDurationMinutes: number
  turnaroundDurationMinutes: number
  startDatetime: string
  endDatetime: string
  effectiveStart: string
  effectiveEnd: string
  timingNote: string | null
  conflicts: VenueBookingConflict[]
  editable?: boolean
}
