import { useState } from 'react'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient } from '../../lib/apiClient'
import { useVenueRead } from './useVenueRead'
import { BookingConflictList, type BookingOverlap } from './BookingConflictList'

type Overlap = BookingOverlap
export function TimingConflictReview({ venueId }: { venueId: string }) {
  const read = useVenueRead<Overlap[]>(`/venues/${venueId}/timing-conflicts`)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  async function scan() {
    setBusy(true); setMessage(''); setError('')
    try {
      const pairs = await apiClient.post<Overlap[]>(`/venues/${venueId}/timing-conflicts/scan`)
      read.replaceData(pairs)
      setMessage(pairs.length ? 'Conflicts checked. Coordinators are notified of newly detected or changed overlaps.' : 'No overlaps between reserved booking periods.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not check conflicts. Please try again.') }
    finally { setBusy(false) }
  }
  if (read.data?.length === 0 && !read.error && !error) return null

  return <section className="venue-timing-conflicts" aria-label="Existing timing conflicts">
    <Card className="feature-skeleton__body">
    <h2>Existing timing conflicts</h2>
    <p>Reserved periods overlap. Review the affected bookings with their coordinators.</p>
    {read.error && <p role="alert">{read.error} <Button onClick={read.retry}>Retry conflicts</Button></p>}
    {!read.data && !read.error && <p role="status">Loading timing conflicts…</p>}
    {Array.isArray(read.data) && <BookingConflictList pairs={read.data} showHeading={false} />}
    <div className="booking-approval__actions">
    <Button variant="secondary" disabled={read.loading || busy} onClick={() => { setMessage(''); read.retry() }}>Refresh conflicts</Button>
    <Button disabled={busy} onClick={() => void scan()}>{busy ? 'Checking…' : 'Check conflicts and notify coordinators'}</Button>
    </div>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    </Card>
  </section>
}
