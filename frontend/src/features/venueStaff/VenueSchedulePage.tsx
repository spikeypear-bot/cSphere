import { useEffect, useRef, useState } from 'react'
import type { VenueDto } from '../../types/venue'
import { Button } from '../../components/ui/Button'
import { useVenueRead } from './useVenueRead'
import type { VenueSchedule } from './VenueUnavailabilityPanel'
import { formatEventTimeRange as range } from './formatEventDateTime'
import { calendarDays, calendarEntries, dayTime, entriesOnDay, entryLabels, midnight, shiftMonth, singaporeDate } from './venueCalendar'
import type { CalendarEntry } from './venueCalendar'
import './VenueSchedulePage.css'

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
  return <div className="feature-skeleton venue-calendar">
    <h1>Venue availability calendar</h1>
    <p>All times are in Singapore time (SGT). Event, setup and turnaround times appear as separate entries. Tentative holds are pending requests and do not reserve the venue.</p>
    {venues.error ? <p role="alert">{venues.error} <Button onClick={venues.retry}>Retry venues</Button></p>
      : !venues.data ? <p role="status">Loading venues…</p> : !venues.data.length ? <p>No venues available.</p> : <div className="field venue-calendar__venue">
        <label htmlFor="calendar-venue">Venue</label>
        <select id="calendar-venue" value={selected} onChange={e => { preserveScheduleHeight(); setSelected(e.target.value) }}><option value="">Select a venue</option>
          {venues.data.map(v => <option key={v.venueId} value={v.venueId}>{v.venueAddress}</option>)}
        </select>
      </div>}
    <div>
    <div className="venue-calendar__toolbar">
      <h2 aria-live="polite">{title}</h2>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(shiftMonth(month, -1))}>Previous month</Button>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(singaporeDate().slice(0, 7))}>Today</Button>
      <Button type="button" className="venue-calendar__control" onClick={() => changeMonth(shiftMonth(month, 1))}>Next month</Button>
    </div>
    <div ref={scheduleFrame}>
    {selected && venues.data ? <Schedule key={`${selected}:${month}`} venueId={selected} onRefreshStart={preserveScheduleHeight}
      venueName={venues.data.find(v => v.venueId === selected)?.venueAddress ?? selected} month={month} />
      : <><CalendarLegend /><p>Select a venue to view its schedule.</p></>}
    </div>
    </div>
  </div>
}

function CalendarLegend({ onRefresh, loading = false }: { onRefresh?: () => void; loading?: boolean }) {
  return <div className="venue-calendar__status-row">
    <ul className="venue-calendar__legend" aria-label="Calendar legend">
      {Object.entries(entryLabels).map(([type, label]) => <li key={type} data-kind={type}>{label}</li>)}
    </ul>
    {onRefresh && <Button type="button" className="venue-calendar__control" disabled={loading} onClick={onRefresh}>Refresh schedule</Button>}
  </div>
}

function Schedule({ venueId, venueName, month, onRefreshStart }: { venueId: string; venueName: string; month: string; onRefreshStart: () => void }) {
  const days = calendarDays(month)
  const end = new Date(Date.parse(`${days.at(-1)}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)
  const query = new URLSearchParams({ start: midnight(days[0]), end: midnight(end) })
  const read = useVenueRead<VenueSchedule>(`/venues/${venueId}/schedule?${query}`)
  const [selected, setSelected] = useState<CalendarEntry | null>(null)
  const refresh = () => { onRefreshStart(); setSelected(null); read.retry() }
  if (read.error) return <section aria-label="Venue schedule"><CalendarLegend onRefresh={refresh} /><p role="alert">{read.error} <Button type="button" onClick={refresh}>Retry schedule</Button></p></section>
  if (!read.data) return <section aria-label="Venue schedule" aria-busy="true"><CalendarLegend onRefresh={refresh} loading /><p role="status">Loading schedule…</p></section>
  const entries = calendarEntries(read.data)
  return <section aria-label="Venue schedule">
    <CalendarLegend onRefresh={refresh} />
    {!days.filter(day => day.startsWith(month)).some(day => entriesOnDay(entries, day).length) && <p role="status">No schedule entries for this month.</p>}
    <p className="venue-calendar__hint">Select an entry for details. Arrows indicate that an entry continues across dates.</p>
    <div className="venue-calendar__scroll" role="region" aria-label="Monthly calendar" tabIndex={0}>
      <table className="venue-calendar__grid">
        <caption className="venue-calendar__caption">{venueName} — {month} (SGT)</caption>
        <thead><tr>{['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(day => <th scope="col" key={day}>{day}</th>)}</tr></thead>
        <tbody>{Array.from({ length: days.length / 7 }, (_, week) => <tr key={week}>
          {days.slice(week * 7, week * 7 + 7).map(day => <td key={day} data-outside={!day.startsWith(month)}>
            <time dateTime={day} aria-current={day === singaporeDate() ? 'date' : undefined}>{Number(day.slice(-2))}</time>
            <ul>{entriesOnDay(entries, day).map(entry => <li key={entry.id}>
              <button className="venue-calendar__entry" data-kind={entry.type}
                aria-label={`${entryLabels[entry.type]} · ${entry.phase}: ${entry.title}, ${day}, ${dayTime(entry, day)}`}
                onClick={() => setSelected(entry)}>
                <span>{entryLabels[entry.type]}{entry.phase !== 'Unavailable' ? ` · ${entry.phase}` : ''}</span>
                <strong>{entry.title}</strong><span>{dayTime(entry, day)}</span>
              </button>
            </li>)}</ul>
          </td>)}
        </tr>)}</tbody>
      </table>
    </div>
    {selected && <EntryDetails entry={selected} venueName={venueName} onClose={() => setSelected(null)} />}
  </section>
}

function EntryDetails({ entry, venueName, onClose }: { entry: CalendarEntry; venueName: string; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="venue-calendar__details" aria-labelledby="calendar-detail-title" onCancel={onClose} onClose={onClose}>
    <h2 id="calendar-detail-title">{entry.type === 'unavailable' ? 'Unavailable period' : entry.title}</h2>
    <dl>
      <dt>Venue</dt><dd>{venueName}</dd>
      <dt>Type</dt><dd>{entryLabels[entry.type]}</dd>
      <dt>Entry</dt><dd>{entry.phase}</dd>
      <dt>Start / end (SGT)</dt><dd>{range(entry.start, entry.end)}</dd>
      {entry.eventStart && entry.eventEnd && entry.phase !== 'Event' && <><dt>Event timing (SGT)</dt><dd>{range(entry.eventStart, entry.eventEnd)}</dd></>}
      {entry.type === 'unavailable' && <><dt>Reason</dt><dd>{entry.title}</dd></>}
    </dl>
    <Button onClick={onClose}>Close</Button>
  </dialog>
}
