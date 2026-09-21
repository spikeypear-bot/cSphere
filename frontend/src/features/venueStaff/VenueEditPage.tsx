import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { VenueDto } from '../../types/venue'
import { VenueCreatePage } from './VenueCreatePage'

export function VenueEditPage() {
  const { venueId } = useParams()
  // Remount the loader when the selected record changes, avoiding stale forms/errors.
  return <VenueEditLoader key={venueId} venueId={venueId!} />
}

function VenueEditLoader({ venueId }: { venueId: string }) {
  const [venue, setVenue] = useState<VenueDto | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    apiClient.get<VenueDto>(`/venues/${venueId}`).then(data => { if (active) setVenue(data) })
      .catch((err: unknown) => { if (active) setError(err instanceof ApiClientError ? err.message : 'Could not load the venue. Please try again.') })
    return () => { active = false }
  }, [venueId, attempt])
  if (error) return <div className="feature-skeleton">
    <Link to="/venue-staff/catalogue">Back to venue catalogue</Link>
    <p role="alert">{error}</p>
    <Button onClick={() => { setError(''); setAttempt(value => value + 1) }}>Try again</Button>
  </div>
  if (!venue) return <p role="status">Loading venue…</p>
  return <VenueCreatePage initialVenue={venue} />
}
