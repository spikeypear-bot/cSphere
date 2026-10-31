import { VenueMapPanel } from '../venueMap/VenueMapPanel'
import type { VenueMapProps } from '../venueMap/VenueMap'
import './CoordinatorVenueMap.css'

export function CoordinatorVenueMap(props: Omit<VenueMapProps, 'caption'>) {
  return <div className="coordinator-venue-map">
    <VenueMapPanel {...props} caption={props.onToggleVenue
      ? 'Open a location to choose individual venues. Highlighted venues are selected for your request.'
      : 'Search for available venues, then select a location to find its venues in the results.'} />
  </div>
}
