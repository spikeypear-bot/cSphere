export interface VenueHold {
  holdId: string
  bookingId: string
  venueId: string
  eventId: string
  eventName: string
  reservedStart: string
  reservedEnd: string
  expiresAt: string
  /** EC-C3: when the hold enters the reminder window (expiry minus the server's reminder lead). */
  reminderDueAt?: string
  status: 'active' | 'expired' | 'released' | 'converted'
  createdBy: string
  createdAt: string
  endedAt: string | null
  serverTime: string
}
