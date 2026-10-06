import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { VenueDto } from '../../types/venue'
import { Button } from '../../components/ui/Button'
import { useVenueRead } from './useVenueRead'
import type { VenueSchedule } from './VenueUnavailabilityPanel'
import { formatEventTimeRange as range } from './formatEventDateTime'
import './VenueUnavailability.css'

export function VenueSchedulePage() {
  const venues = useVenueRead<VenueDto[]>('/venues')
  const [selected, setSelected] = useState('')
  return <div className="feature-skeleton">
    <h1>Venue availability calendar</h1>
    <p>Chronological schedule in Singapore time. Bookings include setup and turnaround. Pending requests do not reserve a venue.</p>
    {venues.error ? <p role="alert">{venues.error} <Button onClick={venues.retry}>Retry venues</Button></p>
      : !venues.data ? <p>Loading venues…</p> : <label>Venue{' '}
        <select value={selected} onChange={e => setSelected(e.target.value)}><option value="">Select a venue</option>
          {venues.data.map(v => <option key={v.venueId} value={v.venueId}>{v.venueAddress}</option>)}
        </select>
      </label>}
    {selected && <Schedule key={selected} venueId={selected} />}
  </div>
}

function Schedule({ venueId }: { venueId: string }) {
  const read = useVenueRead<VenueSchedule>(`/venues/${venueId}/schedule`)
  if (read.error) return <p role="alert">{read.error} <Button onClick={read.retry}>Retry schedule</Button></p>
  if (!read.data) return <p>Loading schedule…</p>
  const entries = [
    ...read.data.bookings.map(b => ({ id: b.bookingId, start: b.effectiveStart, end: b.effectiveEnd,
      label: `${b.status === 'approved' ? 'Booked' : 'Pending request'}: ${b.eventName}`,
      kind: b.status === 'approved' ? 'booking' : 'pending', to: `/venue-staff/bookings/${b.bookingId}` })),
    ...read.data.unavailablePeriods.map(p => ({ id: p.unavailabilityId, start: p.startDateTime, end: p.endDateTime,
      label: `Unavailable: ${p.reason}`, kind: 'unavailable', to: `/venue-staff/catalogue/${venueId}` })),
  ].sort((a, b) => Date.parse(a.start) - Date.parse(b.start))
  return <section aria-label="Venue schedule">
    <p>Setup: {read.data.settings.setupMinutes} min · Turnaround: {read.data.settings.turnaroundMinutes} min</p>
    <Button onClick={read.retry}>Refresh schedule</Button>
    {!entries.length ? <p>No bookings or unavailable periods recorded.</p> : <ul className="venue-schedule-list">{entries.map(e =>
      <li key={e.id} data-kind={e.kind}><strong>{e.label}</strong><p>{range(e.start, e.end)}</p><Link to={e.to}>View details</Link></li>)}</ul>}
  </section>
}
