import type { ReactNode } from 'react'
import { OperatingHours } from '../OperatingHours'
import { venueAccessibilityLabels, venueFacilityLabels, venueLayoutLabels, type VenueDto } from '../../types/venue'

/** Everything a venue offers, as labelled facts — the body of a venue's
 * details window. `children` adds page-specific facts (e.g. booking timings). */
export function VenueDetails({ venue, children }: { venue: VenueDto; children?: ReactNode }) {
  const list = (values: string[]) => values.join(', ') || 'None recorded'
  return (
    <>
      <dl className="dash-figures dash-facts">
        <div><dt>Capacity</dt><dd>{venue.venueCapacity == null ? 'Not recorded' : `${venue.venueCapacity.toLocaleString()} people`}</dd></div>
        <div><dt>Supported layouts</dt><dd>{list(venue.supportedLayouts.map((l) => venueLayoutLabels[l] ?? l))}</dd></div>
        <div><dt>Facilities</dt><dd>{list(venue.venueFacilities.map((f) => venueFacilityLabels[f] ?? f))}</dd></div>
        <div><dt>Accessibility</dt><dd>{list(venue.venueAccessibilities.map((a) => venueAccessibilityLabels[a] ?? a))}</dd></div>
      </dl>
      <dl className="dash-figures dash-facts">
        <div><dt>Operating hours</dt><dd><OperatingHours hours={venue.operatingHours} /></dd></div>
        {venue.additionalInformation?.trim()
          ? <div><dt>Additional information</dt><dd>{venue.additionalInformation}</dd></div>
          : null}
      </dl>
      {children}
    </>
  )
}
