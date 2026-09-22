import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { venueLayoutLabels, venueAccessibilityLabels, venueFacilityLabels, type VenueDto } from '../../types/venue'
import '../../components/skeleton.css'
import './VenueCataloguePage.css'

export function VenueDetailsPanel({ venue, linkToDetails = false }: { venue: VenueDto; linkToDetails?: boolean }) {
  return <Card className="feature-skeleton__body venue-card-preview">
    <div className="venue-card-preview__header">
      <p className="venue-card-preview__id" aria-label="Venue ID">{venue.venueId}</p>
      <Link className="button button--secondary venue-card-preview__edit" to={`/venue-staff/catalogue/${venue.venueId}/edit`}>Edit venue</Link>
    </div>
    <ul className="venue-card-preview__tags" aria-label="Supported layouts">
      {venue.supportedLayouts.map(layout => <li key={layout} className="venue-card-preview__tag">
        {venueLayoutLabels[layout] ?? layout}
      </li>)}
    </ul>
    <h2 className="venue-card-preview__title">{venue.venueAddress}</h2>
    <p className="venue-card-preview__capacity">{venue.venueCapacity?.toLocaleString()} people</p>
    <dl className="venue-card-preview__information">
      <div><dt>Operating information</dt><dd>{venue.operatingInformation}</dd></div>
      <div><dt>Accessibility provisions</dt>
        <dd>{venue.venueAccessibilities.map(value => venueAccessibilityLabels[value]).join(', ') || 'Not recorded'}</dd></div>
      <div><dt>Facilities</dt>
        <dd>{venue.venueFacilities.map(value => venueFacilityLabels[value]).join(', ') || 'Not recorded'}</dd></div>
      <div><dt>Additional information</dt><dd>{venue.additionalInformation?.trim() || 'No Additional Information'}</dd></div>
    </dl>
    {linkToDetails && <Link className="button button--secondary venue-card-preview__details" to={`/venue-staff/catalogue/${venue.venueId}`}>View venue details</Link>}
  </Card>
}

