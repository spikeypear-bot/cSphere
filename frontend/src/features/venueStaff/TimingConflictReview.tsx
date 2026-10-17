import { useState } from 'react'
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
      setMessage(pairs.length ? 'Conflicts checked. Coordinators are notified of newly detected or changed overlaps.' : 'No overlaps between active approved bookings.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not check conflicts. Please try again.') }
    finally { setBusy(false) }
  }
  return <section aria-label="Existing timing conflicts">
    <h2>Existing timing conflicts</h2>
    <p>Review overlapping approved bookings with the assigned coordinators. Checking preserves every booking.</p>
    {read.error && <p role="alert">{read.error} <Button onClick={read.retry}>Retry conflicts</Button></p>}
    {!read.data && !read.error && <p role="status">Loading timing conflicts…</p>}
    {Array.isArray(read.data) && <BookingConflictList pairs={read.data} />}
    <Button disabled={read.loading || busy} onClick={() => { setMessage(''); read.retry() }}>Refresh conflicts</Button>
    <Button disabled={busy} onClick={() => void scan()}>{busy ? 'Checking…' : 'Check conflicts and notify coordinators'}</Button>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>
}
