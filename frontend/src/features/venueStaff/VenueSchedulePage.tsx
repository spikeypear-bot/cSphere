import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import type { VenueDto } from '../../types/venue'
import { Button } from '../../components/ui/Button'
import { useVenueRead } from './useVenueRead'
import type { VenueSchedule } from './VenueUnavailabilityPanel'
import { formatEventTimeRange as range } from './formatEventDateTime'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'
import { formatEventDateTime } from './formatEventDateTime'
import { calendarDays, calendarEntries, dayTime, entriesOnDay, entryLabels, midnight, shiftMonth, singaporeDate } from './venueCalendar'
import type { CalendarEntry } from './venueCalendar'
import './VenueSchedulePage.css'
import { Link } from 'react-router-dom'
import { approvedOverlaps } from './venueCalendar'
import { BookingConflictList, type BookingOverlap } from './BookingConflictList'

export function VenueSchedulePage() {
  const venues = useVenueRead<VenueDto[]>('/venues')
  const [selected, setSelected] = useState('')
  const [month, setMonth] = useState(() => singaporeDate().slice(0, 7))
  const scheduleFrame = useRef<HTMLDivElement>(null)
  // Keep the document tall enough while replacing a calendar with loading/empty content.
  // Otherwise the browser clamps the scroll position before the new data arrives.
  const preserveScheduleHeight = () => {
    const frame = scheduleFrame.current
    if (frame) frame.style.minHeight = `${frame.getBoundingClientRect().height}px`
  }
  const changeMonth = (next: string) => { preserveScheduleHeight(); setMonth(next) }
  const title = new Intl.DateTimeFormat('en-SG', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${month}-01T00:00:00Z`))
  const toolbar = <div className="venue-calendar__toolbar">
      <h2 aria-live="polite">{title}</h2>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(shiftMonth(month, -1))}>Previous month</Button>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(singaporeDate().slice(0, 7))}>Today</Button>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(shiftMonth(month, 1))}>Next month</Button>
    </div>
  return <div className="feature-skeleton venue-calendar">
    <h1>Venue availability calendar</h1>
    <p>Singapore time. Bookings requiring attention and active holds still reserve their occupied periods. Pending requests do not reserve the venue.</p>
    {venues.error ? <p role="alert">{venues.error} <Button onClick={venues.retry}>Retry venues</Button></p>
      : !venues.data ? <p role="status">Loading venues…</p> : !venues.data.length ? <p>No venues available.</p> : <div className="field venue-calendar__venue">
        <label htmlFor="calendar-venue">Venue</label>
        <select id="calendar-venue" value={selected} onChange={e => { preserveScheduleHeight(); setSelected(e.target.value) }}><option value="">Select a venue</option>
          {venues.data.map(v => <option key={v.venueId} value={v.venueId}>{v.venueAddress}</option>)}
        </select>
      </div>}
    <div>
    <div ref={scheduleFrame}>
    {selected && venues.data ? <Schedule key={`${selected}:${month}`} venueId={selected} onRefreshStart={preserveScheduleHeight}
      venueName={venues.data.find(v => v.venueId === selected)?.venueAddress ?? selected} month={month} toolbar={toolbar} />
      : <><CalendarLegend /><p>Select a venue to view its schedule.</p>{toolbar}</>}
    </div>
    </div>
  </div>
}

function CalendarLegend({ onRefresh, loading = false }: { onRefresh?: () => void; loading?: boolean }) {
  return <div className="venue-calendar__status-row">
    <ul className="venue-calendar__legend" aria-label="Calendar legend">
      {Object.entries(entryLabels).map(([type, label]) => <li key={type} data-kind={type} data-booking-status={type === 'unavailable' ? undefined : type}>{label}</li>)}
      <li className="venue-calendar__conflict-legend"><span aria-hidden="true">⚠</span><span>Conflict</span></li>
    </ul>
    {onRefresh && <Button type="button" className="venue-calendar__control" disabled={loading} onClick={onRefresh}>Refresh schedule</Button>}
  </div>
}

function Schedule({ venueId, venueName, month, onRefreshStart, toolbar }: { toolbar: ReactNode; venueId: string; venueName: string; month: string; onRefreshStart: () => void }) {
  const days = calendarDays(month)
  const end = new Date(Date.parse(`${days.at(-1)}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)
  const query = new URLSearchParams({ start: midnight(days[0]), end: midnight(end) })
  const read = useVenueRead<VenueSchedule>(`/venues/${venueId}/schedule?${query}`, false, 5000)
  const [selectedId, setSelected] = useState<string | null>(null)
  const expired = useHoldDeadline(read.data?.holds ?? NO_HOLDS, read.retry)
  const refresh = () => { onRefreshStart(); setSelected(null); read.retry() }
  if (read.error) return <section aria-label="Venue schedule"><CalendarLegend onRefresh={refresh} /><p role="alert">{read.error} <Button type="button" onClick={refresh}>Retry schedule</Button></p></section>
  if (!read.data) return <section aria-label="Venue schedule" aria-busy="true"><CalendarLegend onRefresh={refresh} loading /><p role="status">Loading schedule…</p></section>
  const entries = calendarEntries(read.data, expired)
  const selected = entries.find(entry => entry.id === selectedId)
  const activeConflicts = read.data.activeConflicts ?? approvedOverlaps(read.data)
  // Keep historical calendar overlaps while also identifying off-screen active partners.
  const overlaps = [...new Map([...approvedOverlaps(read.data), ...activeConflicts]
    .map(pair => [[pair.first.bookingId, pair.second.bookingId].sort().join(':'), pair])).values()]
  const conflictingIds = new Set(overlaps.flatMap(pair => [pair.first.bookingId, pair.second.bookingId]))
  return <section aria-label="Venue schedule">
    <CalendarLegend onRefresh={refresh} />
    {activeConflicts.length > 0 && <div className="venue-calendar__conflicts">
      <BookingConflictList pairs={activeConflicts} venueName={venueName} />
      <Link to={`/venue-staff/catalogue/${venueId}`}>Review all active conflicts for this venue</Link>
    </div>}
    {!days.filter(day => day.startsWith(month)).some(day => entriesOnDay(entries, day).length) && <p role="status">No schedule entries for this month.</p>}
    <p className="venue-calendar__hint">Select an entry for details. Arrows indicate that an entry continues across dates.</p>
    {toolbar}
    <div className="venue-calendar__scroll" role="region" aria-label="Monthly calendar" tabIndex={0}>
      <table className="venue-calendar__grid" aria-label={`${venueName} — ${month} (SGT)`}>
        <thead><tr>{['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(day => <th scope="col" key={day}>{day}</th>)}</tr></thead>
        <tbody>{Array.from({ length: days.length / 7 }, (_, week) => <tr key={week}>
          {days.slice(week * 7, week * 7 + 7).map(day => <td key={day} data-outside={!day.startsWith(month)}>
            <time dateTime={day} aria-current={day === singaporeDate() ? 'date' : undefined}>{Number(day.slice(-2))}</time>
            <ul>{entriesOnDay(entries, day).map(entry => <li key={entry.id}>
              <button className="venue-calendar__entry" data-kind={entry.type} data-booking-status={entry.type === 'unavailable' ? undefined : entry.type}
                data-conflict={(entry.type === 'approved' || entry.type === 'attention') && conflictingIds.has(entry.entryId)}
                aria-label={`${entryLabels[entry.type]} · ${entry.phase}: ${entry.title}, ${day}, ${dayTime(entry, day)}${(entry.type === 'approved' || entry.type === 'attention') && conflictingIds.has(entry.entryId) ? ', Conflict' : ''}`}
                onClick={() => setSelected(entry.id)}>
                <span>{entryLabels[entry.type]}{entry.phase !== 'Unavailable' ? ` · ${entry.phase}` : ''}</span>
                <strong>{entry.title}</strong><span>{dayTime(entry, day)}</span>
                {(entry.type === 'approved' || entry.type === 'attention') && conflictingIds.has(entry.entryId) && <span>⚠ Conflict</span>}
              </button>
            </li>)}</ul>
          </td>)}
        </tr>)}</tbody>
      </table>
    </div>
    {selected && <EntryDetails entry={selected} venueName={venueName}
      conflicts={overlaps.filter(pair => pair.first.bookingId === selected.entryId || pair.second.bookingId === selected.entryId)}
      onClose={() => setSelected(null)} />}
  </section>
}

function EntryDetails({ entry, venueName, conflicts, onClose }: { entry: CalendarEntry; venueName: string; conflicts: BookingOverlap[]; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="venue-calendar__details" aria-labelledby="calendar-detail-title" onCancel={onClose} onClose={onClose}>
    <h2 id="calendar-detail-title">{entry.type === 'unavailable' ? 'Unavailable period' : entry.title}</h2>
    <dl>
      <dt>Venue</dt><dd>{venueName}</dd>
      <dt>Type</dt><dd>{entryLabels[entry.type]}</dd>
      <dt>Entry</dt><dd>{entry.phase}</dd>
      {entry.expiresAt && <><dt>Hold expiry (SGT)</dt><dd>{formatEventDateTime(entry.expiresAt)}</dd><dt>Hold status</dt><dd>Active</dd></>}
      <dt>Start / end (SGT)</dt><dd>{range(entry.start, entry.end)}</dd>
      {entry.eventStart && entry.eventEnd && entry.phase !== 'Event' && <><dt>Event timing (SGT)</dt><dd>{range(entry.eventStart, entry.eventEnd)}</dd></>}
      {entry.type === 'unavailable' && <><dt>Reason</dt><dd>{entry.title}</dd></>}
    </dl>
    {conflicts.length > 0 && <BookingConflictList pairs={conflicts} venueName={venueName} />}
    <div className="venue-calendar__detail-actions">
      {entry.type !== 'unavailable' && <Link to={`/venue-staff/bookings/${entry.entryId}`}>View booking details</Link>}
      <Button onClick={onClose}>Close</Button>
    </div>
  </dialog>
}
