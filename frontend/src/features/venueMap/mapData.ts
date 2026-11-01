import type { Facility, VenueDto } from '../../types/venue'
import type { VenueBookingStatus } from '../../types/venueBookingStatus'

export interface VenueMapActivity {
  venueId: string
  eventCount: number
  pendingBookingCount: number
  matchingBookingCount: number
}
export interface VenueMapBooking {
  bookingId: string
  eventId: string
  eventName: string
  status: VenueBookingStatus
  startDatetime: string
  endDatetime: string
  holdExpiresAt: string | null
  serverTime: string
  past: boolean
}
export function venueName(venue: VenueDto) { return venue.venueName?.trim() || venue.venueAddress }

export function hasCoordinates(venue: VenueDto): venue is VenueDto & { latitude: number; longitude: number } {
  return typeof venue.latitude === 'number' && Number.isFinite(venue.latitude) && Math.abs(venue.latitude) <= 90
    && typeof venue.longitude === 'number' && Number.isFinite(venue.longitude) && Math.abs(venue.longitude) <= 180
}

/** Group rooms within ~25 metres, retaining each room's original UUID and coordinates. */
export function groupVenues(venues: VenueDto[]) {
  const groups: { position: [number, number]; venues: VenueDto[] }[] = []
  for (const venue of venues.filter(hasCoordinates)) {
    const group = groups.find(g => Math.hypot((g.position[0] - venue.latitude) * 111320,
      (g.position[1] - venue.longitude) * 111320 * Math.cos(venue.latitude * Math.PI / 180)) <= 25)
    if (group) group.venues.push(venue)
    else groups.push({ position: [venue.latitude, venue.longitude], venues: [venue] })
  }
  return groups
}

export function filterVenues(venues: VenueDto[], query: string, capacity: string, facilities: Facility[],
  date: string, activity: VenueMapActivity[]) {
  const needle = query.trim().toLocaleLowerCase()
  const matching = new Set(activity.filter(a => a.matchingBookingCount > 0).map(a => a.venueId))
  return venues.filter(v => `${venueName(v)} ${v.venueAddress}`.toLocaleLowerCase().includes(needle)
    && (!capacity || (v.venueCapacity !== null && v.venueCapacity >= Number(capacity)))
    && facilities.every(f => v.venueFacilities.includes(f)) && (!date || matching.has(v.venueId)))
}
