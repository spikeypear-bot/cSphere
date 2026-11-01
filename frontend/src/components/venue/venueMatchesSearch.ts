import type { VenueDto } from '../../types/venue'

/** EC's local text filter is identical for the result catalogue and its map. */
export function venueMatchesSearch(venue: VenueDto, query: string) {
  return `${venue.venueName ?? ''} ${venue.venueAddress} ${venue.additionalInformation ?? ''}`
    .toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())
}
