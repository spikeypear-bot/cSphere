import { OperatingHours } from '../../components/OperatingHours'
import { Link, useLocation } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { venueLayoutLabels, venueAccessibilityLabels, venueFacilityLabels, type VenueDto } from '../../types/venue'
import '../../components/skeleton.css'
import './VenueCataloguePage.css'

export function VenueDetailsPanel({ venue, linkToDetails = false }: { venue: VenueDto; linkToDetails?: boolean }) {
  const location = useLocation()
  const returnTo = location.pathname + location.search + location.hash
  return <Card className="feature-skeleton__body venue-card-preview">
    <div className="venue-card-preview__header">
      <p className="venue-card-preview__id" aria-label="Venue ID">{venue.venueId}</p>
      <Link className="button button--secondary venue-card-preview__edit" to={`/venue-staff/catalogue/${venue.venueId}/edit`} state={{ returnTo }}>Edit venue</Link>
    </div>
    <ul className="venue-card-preview__tags" aria-label="Supported layouts">
      {venue.supportedLayouts.map(layout => <li key={layout} className="venue-card-preview__tag">
        {venueLayoutLabels[layout] ?? layout}
      </li>)}
    </ul>
    <h2 className="venue-card-preview__title">{venue.venueAddress}</h2>
    <p className="venue-card-preview__capacity">{venue.venueCapacity?.toLocaleString()} people</p>
    <dl className="venue-card-preview__information">
      <div><dt>Operating hours</dt><dd><OperatingHours hours={venue.operatingHours} /></dd></div>
      <div><dt>Accessibility provisions</dt>
        <dd>{venue.venueAccessibilities.map(value => venueAccessibilityLabels[value]).join(', ') || 'Not recorded'}</dd></div>
      <div><dt>Facilities</dt>
        <dd>{venue.venueFacilities.map(value => venueFacilityLabels[value]).join(', ') || 'Not recorded'}</dd></div>
      <div><dt>Default setup / turnaround</dt><dd>{venue.setupMinutes ?? 0} / {venue.turnaroundMinutes ?? 0} minutes (new requests only)</dd></div>
      <div><dt>Additional information</dt><dd>{venue.additionalInformation?.trim() || 'No Additional Information'}</dd></div>
    </dl>
    {linkToDetails && <Link className="button button--secondary venue-card-preview__details" to={`/venue-staff/catalogue/${venue.venueId}`}>View venue details</Link>}
  </Card>
}

