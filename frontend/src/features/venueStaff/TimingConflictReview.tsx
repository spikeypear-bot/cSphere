import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { apiClient } from '../../lib/apiClient'
import { useVenueRead } from './useVenueRead'
import { formatEventTimeRange as range } from './formatEventDateTime'
import type { ScheduleBooking } from './VenueUnavailabilityPanel'

interface Overlap { first: ScheduleBooking; second: ScheduleBooking }
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
      setMessage(pairs.length ? 'Conflicts checked. Coordinators are notified of newly detected or changed overlaps.' : 'No overlaps between active approved bookings.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not check conflicts. Please try again.') }
    finally { setBusy(false) }
  }
  return <section aria-label="Existing timing conflicts">
    <h2>Existing timing conflicts</h2>
    <p>Review overlapping approved bookings with the assigned coordinators. Checking preserves every booking.</p>
    {read.error && <p role="alert">{read.error} <Button onClick={read.retry}>Retry conflicts</Button></p>}
    {!read.data && !read.error && <p role="status">Loading timing conflicts…</p>}
    {Array.isArray(read.data) && (read.data.length ? <ul>{read.data.map(pair => <li key={`${pair.first.bookingId}:${pair.second.bookingId}`}>
      {[pair.first, pair.second].map(b => <p key={b.bookingId}><Link to={`/venue-staff/bookings/${b.bookingId}`}>{b.eventName}</Link>
        {' — occupied '}{range(b.effectiveStart, b.effectiveEnd)}</p>)}
    </li>)}</ul> : <p>No overlaps between active approved bookings.</p>)}
    <Button disabled={busy} onClick={() => void scan()}>{busy ? 'Checking…' : 'Check conflicts and notify coordinators'}</Button>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>
}
