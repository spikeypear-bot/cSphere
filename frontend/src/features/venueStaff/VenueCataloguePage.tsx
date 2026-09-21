import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { venueLayoutLabels, venueAccessibilityLabels, venueFacilityLabels, type VenueDto } from '../../types/venue'
import '../../components/skeleton.css'
import './VenueCataloguePage.css'

function VenueCatalogueCard({ venue }: { venue: VenueDto }) {
  return <Card className="feature-skeleton__body venue-card-preview">
    <p className="venue-card-preview__id" aria-label="Venue ID">{venue.venueId}</p>
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
  </Card>
}

export function VenueCataloguePage() {
  const location = useLocation()
  const navigate = useNavigate()
  const [venues, setVenues] = useState<VenueDto[] | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    apiClient.get<VenueDto[]>('/venues').then(data => { if (active) setVenues(data) })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiClientError ? err.message : 'Could not load venues. Please try again.') })
    return () => { active = false }
  }, [attempt])
  return <div className="feature-skeleton">
    <div className="feature-skeleton__header"><h1>Venue Catalogue</h1><Link className="button button--primary" to="/venue-staff/catalogue/new">Add a venue</Link></div>
    <p className="feature-skeleton__summary">View saved venue details for event planning.</p>
    {location.state?.venueSaved && <Card className="feature-skeleton__body venue-success-banner"><p role="status">Venue saved successfully.</p>
      <Button variant="ghost" onClick={() => navigate(location.pathname, { replace: true, state: null })}>Dismiss</Button></Card>}
    {error ? <Card className="feature-skeleton__body"><p role="alert">{error}</p><Button variant="secondary" onClick={() => { setError(''); setAttempt(n => n + 1) }}>Try again</Button></Card>
      : venues === null ? <p role="status">Loading venues…</p>
      : venues.length === 0 ? <Card className="feature-skeleton__body"><h2>No venues yet</h2><p>Add your first venue to start the catalogue.</p></Card>
      : venues.map(venue => <VenueCatalogueCard key={venue.venueId} venue={venue} />)}
  </div>
}
