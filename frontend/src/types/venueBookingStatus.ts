import type { VenueHold } from './venueHold'

/** Mirrors VenueBookingStatus.java; hold lifecycle is deliberately independent. */
export type VenueBookingStatus = 'pending' | 'approved' | 'changed' | 'rejected' | 'cancelled'
export type VenueBookingDisplayStatus = VenueBookingStatus | 'tentative'

export const BOOKING_STATUS_LABELS: Record<VenueBookingDisplayStatus, string> = {
  pending: 'Pending', tentative: 'Tentative Hold', approved: 'Approved',
  changed: 'Replaced', rejected: 'Rejected', cancelled: 'Cancelled',
}

export function bookingDisplayStatus(status: VenueBookingStatus, holds: VenueHold[] = [], expired = new Set<string>()): VenueBookingDisplayStatus {
  return status === 'pending' && holds.some(h => h.status === 'active' && !expired.has(h.holdId)) ? 'tentative' : status
}
