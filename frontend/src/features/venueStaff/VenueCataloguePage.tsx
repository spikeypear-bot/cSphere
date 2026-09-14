import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { venueLayoutLabels, type VenueDto } from '../../types/venue'
import '../../components/skeleton.css'
import './VenueCataloguePage.css'

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
      : venues.map(venue => <Card key={venue.venueId} className="feature-skeleton__body">
        <h2>{venue.venueAddress}</h2><dl>
          <dt>Venue ID</dt><dd>{venue.venueId}</dd>
          <dt>Overall capacity</dt><dd>{venue.venueCapacity?.toLocaleString()} people</dd>
          <dt>Supported layouts</dt><dd>{venue.supportedLayouts.map(layout => venueLayoutLabels[layout] ?? layout).join(', ')}</dd>
          <dt>Operating information</dt><dd>{venue.operatingInformation}</dd>
          {venue.additionalInformation && <><dt>Additional information</dt><dd>{venue.additionalInformation}</dd></>}
        </dl></Card>)}
  </div>
}
