import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { useVenueRead } from './useVenueRead'
import type { VenueDto } from '../../types/venue'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import '../../components/skeleton.css'
import './VenueCataloguePage.css'

export function VenueCataloguePage() {
  const location = useLocation()
  const navigate = useNavigate()
  const { data: venues, error, retry, loading } = useVenueRead<VenueDto[]>('/venues')
  return <div className="feature-skeleton">
    <PageHeader
      title="Venue Catalogue"
      description="Each venue is an independently bookable room or space, identified by its address and room details."
      actions={<>
        <Button className="venue-refresh-button" variant="ghost" onClick={retry}
          disabled={loading} aria-label="Refresh venues" title="Refresh venues">
          <RefreshIcon />
          <span>Refresh</span>
        </Button>
        <Link className="button button--primary" to="/venue-staff/catalogue/new">Add a venue</Link>
      </>}
    />
    {location.state?.venueSaved && <Card className="feature-skeleton__body venue-success-banner"><p role="status">{location.state?.venueUpdated ? 'Venue updated successfully.' : 'Venue saved successfully.'}</p>
      <Button variant="ghost" onClick={() => navigate(location.pathname, { replace: true, state: null })}>Dismiss</Button></Card>}
    {error ? <Card className="feature-skeleton__body"><p role="alert">{error}</p><Button variant="secondary" onClick={retry}>Try again</Button></Card>
      : !venues ? <p role="status">Loading venues…</p>
      : venues.length === 0 ? <Card className="feature-skeleton__body"><h2>No venues yet</h2><p>Add your first venue to start the catalogue.</p></Card>
      : venues.map(venue => <VenueDetailsPanel key={venue.venueId} venue={venue} linkToDetails />)}
  </div>
}
