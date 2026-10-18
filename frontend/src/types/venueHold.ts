export interface VenueHold {
  holdId: string
  bookingId: string
  venueId: string
  eventId: string
  eventName: string
  reservedStart: string
  reservedEnd: string
  expiresAt: string
  status: 'active' | 'expired' | 'released' | 'converted'
  createdBy: string
  createdAt: string
  endedAt: string | null
  serverTime: string
}
