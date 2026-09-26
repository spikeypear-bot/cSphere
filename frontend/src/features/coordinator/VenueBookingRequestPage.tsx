import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventDto } from '../../types/event'
import { ACCESSIBILITY_LABELS, type AccessibilityFeature } from '../../types/eventRequest'
import { venueFacilityLabels, venueLayoutLabels, type Facility, type VenueLayout } from '../../types/venue'
import {
  BOOKING_STATUS_LABELS,
  type EventVenueBookingDto,
  type VenueOptionDto,
  type VenueVerdict,
} from '../../types/venueBookingRequest'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './VenueBookingRequestPage.css'

const MAX_TEXT = 2000

const VERDICT_LABELS: Record<VenueVerdict, string> = {
  suitable: 'Suitable',
  needs_justification: 'Needs justification',
  blocked: 'Not available',
}

function accessibilityLabel(value: string) {
  return ACCESSIBILITY_LABELS[value as AccessibilityFeature] ?? value
}

function timeRange(start: string, end: string) {
  return `${formatEventDateTime(start)} – ${formatEventDateTime(end)}`
}

/**
 * EC03: request a venue for an event in Planning. Every catalogue venue is
 * judged against the event on the server and ranked: bookable first, then
 * ones that need a written justification, then ones that cannot be requested,
 * with the tightest fit first so large halls are not used for small events.
 * Selecting one shows a side-by-side comparison before anything is sent.
 * Submitting creates a *pending* request for Venue Staff; nothing is reserved.
 */
export function VenueBookingRequestPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()
  const [event, setEvent] = useState<EventDto | null>(null)
  const [options, setOptions] = useState<VenueOptionDto[] | null>(null)
  const [bookings, setBookings] = useState<EventVenueBookingDto[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [showBlocked, setShowBlocked] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [notes, setNotes] = useState('')
  const [justification, setJustification] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    Promise.all([
      apiClient.get<EventDto>(`/events/${eventId}`),
      apiClient.get<VenueOptionDto[]>(`/events/${eventId}/venue-options`),
      apiClient.get<EventVenueBookingDto[]>(`/events/${eventId}/venue-bookings`),
    ]).then(([e, o, b]) => {
      if (cancelled) return
      setEvent(e)
      setOptions(o)
      setBookings(b)
    }).catch((err: unknown) => {
      if (cancelled) return
      setLoadError(err instanceof ApiClientError
        ? err.isForbidden ? 'Only the coordinator assigned to this event can request a venue for it.' : err.message
        : 'Could not load this event.')
    })
    return () => {
      cancelled = true
    }
  }, [eventId])

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (options ?? []).filter((o) => (showBlocked || o.verdict !== 'blocked')
      && (!term || `${o.venue.venueAddress} ${o.venue.operatingInformation} ${o.venue.additionalInformation ?? ''}`
        .toLowerCase().includes(term)))
  }, [options, search, showBlocked])

  const counts = useMemo(() => {
    const c: Record<VenueVerdict, number> = { suitable: 0, needs_justification: 0, blocked: 0 }
    options?.forEach((o) => { c[o.verdict] += 1 })
    return c
  }, [options])

  if (loadError) {
    return (
      <div className="venue-request">
        <Link to={eventId ? `/coordinator/events/${eventId}` : '/coordinator'}>Back to event</Link>
        <p role="alert">{loadError}</p>
      </div>
    )
  }
  if (!event || !options || !bookings) return <p>Loading venues…</p>

  const active = bookings.find((b) => b.status === 'pending' || b.status === 'confirmed')
  const selected = options.find((o) => o.venue.venueId === selectedId) ?? null
  const needsJustification = selected?.verdict === 'needs_justification'
  const notesTooLong = notes.trim().length > MAX_TEXT
  const justificationTooLong = justification.trim().length > MAX_TEXT
  const canSubmit = Boolean(selected) && selected?.verdict !== 'blocked' && !notesTooLong && !justificationTooLong
    && (!needsJustification || justification.trim().length > 0)

  async function submit() {
    if (!selected || !eventId) return
    setSubmitting(true)
    setSubmitError(null)
    try {
      await apiClient.post<EventVenueBookingDto>(`/events/${eventId}/venue-bookings`, {
        venueId: selected.venue.venueId,
        bookingNotes: notes.trim() || null,
        suitabilityNote: needsJustification ? justification.trim() : null,
      })
      navigate(`/coordinator/events/${eventId}`, { state: { bookingRequested: selected.venue.venueAddress } })
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'The booking request could not be submitted. Nothing was sent.')
    } finally {
      setSubmitting(false)
    }
  }

  const blockedReason = event.status !== 'pending'
    ? 'Venue booking requests can only be made while the event is in Planning.'
    : active
      ? `This event already has a ${BOOKING_STATUS_LABELS[active.status].toLowerCase()} booking request for ${active.venueAddress}.`
      : null

  return (
    <div className="venue-request">
      <Link to={`/coordinator/events/${event.eventId}`}>Back to event</Link>
      <header>
        <h1>Request a venue</h1>
        <p className="field-hint">for <strong>{event.eventName}</strong></p>
      </header>

      <div className="venue-request__layout">
        <Card className="venue-request__needs" aria-labelledby="needs-heading">
          <h2 id="needs-heading">What the event needs</h2>
          <dl>
            <div><dt>When</dt><dd>{timeRange(event.startDatetime, event.endDatetime)}</dd></div>
            <div><dt>Expected attendance</dt><dd>{event.expectedAttendance.toLocaleString()} people</dd></div>
            <div><dt>Venue requirements</dt><dd>{event.venueRequirements}</dd></div>
            <div><dt>Accessibility</dt><dd>{event.accessibilityNeeds.map(accessibilityLabel).join(', ') || 'None stated'}</dd></div>
            {event.equipmentRequirements ? <div><dt>Equipment</dt><dd>{event.equipmentRequirements}</dd></div> : null}
          </dl>
          <p className="field-hint">The booking uses the event's own date and time.</p>
        </Card>

        <div className="venue-request__main">
          {blockedReason ? (
            <Card className="venue-request__blocked">
              <p role="status">{blockedReason}</p>
              <Link className="button button--secondary" to={`/coordinator/events/${event.eventId}`}>Back to event</Link>
            </Card>
          ) : (
            <>
              <div className="venue-request__toolbar">
                <label className="venue-request__search">
                  <span className="visually-hidden">Search venues</span>
                  <input type="search" placeholder="Search by address or details" value={search}
                    onChange={(e) => setSearch(e.target.value)} />
                </label>
                <p className="field-hint" aria-live="polite">
                  {counts.suitable} suitable · {counts.needs_justification} need justification · {counts.blocked} not available
                </p>
                <label className="venue-request__toggle">
                  <input type="checkbox" checked={showBlocked} onChange={(e) => setShowBlocked(e.target.checked)} />
                  Show unavailable venues
                </label>
              </div>

              {visible.length === 0 ? (
                <Card><p>No venues match. {counts.blocked > 0 && !showBlocked ? 'Try showing unavailable venues to see why.' : ''}</p></Card>
              ) : (
                <ul className="venue-request__options" aria-label="Venues ranked for this event">
                  {visible.map((option) => (
                    <li key={option.venue.venueId}>
                      <VenueOptionCard option={option} attendance={event.expectedAttendance}
                        selected={option.venue.venueId === selectedId}
                        onSelect={() => { setSelectedId(option.venue.venueId); setSubmitError(null) }} />
                    </li>
                  ))}
                </ul>
              )}

              {selected ? (
                <Card className="venue-request__review" aria-labelledby="compare-heading">
                  <h2 id="compare-heading">Compare and submit</h2>
                  <Comparison event={event} option={selected} />

                  {needsJustification ? (
                    <div className="venue-request__field">
                      <label htmlFor="justification">Why is this venue still suitable? (required)</label>
                      <textarea id="justification" rows={3} value={justification}
                        onChange={(e) => setJustification(e.target.value)}
                        placeholder="e.g. Portable ramp hire is arranged for the side entrance." />
                      <span className={justificationTooLong ? 'field-error' : 'field-hint'}>
                        {justification.trim().length}/{MAX_TEXT} · Venue Staff will see this with the request.
                      </span>
                    </div>
                  ) : null}

                  <div className="venue-request__field">
                    <label htmlFor="booking-notes">Notes for Venue Staff (optional)</label>
                    <textarea id="booking-notes" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)}
                      placeholder="Layout, setup or access details that help them decide." />
                    <span className={notesTooLong ? 'field-error' : 'field-hint'}>{notes.trim().length}/{MAX_TEXT}</span>
                  </div>

                  <p className="field-hint">This sends a <strong>pending</strong> request to Venue Staff. The venue is not
                    reserved until they approve it.</p>
                  {submitError ? <p role="alert" className="venue-request__error">{submitError}</p> : null}
                  <Button disabled={!canSubmit || submitting} onClick={() => void submit()}>
                    {submitting ? 'Submitting…' : `Request ${selected.venue.venueAddress}`}
                  </Button>
                </Card>
              ) : null}
            </>
          )}

          {bookings.length > 0 ? (
            <Card className="venue-request__history">
              <h2>Earlier requests for this event</h2>
              <ul>
                {bookings.map((b) => (
                  <li key={b.bookingId}>
                    <strong>{b.venueAddress ?? 'Unknown venue'}</strong>: {BOOKING_STATUS_LABELS[b.status]}
                    {b.rejectReason ? <> · Reason: {b.rejectReason}</> : null}
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}
        </div>
      </div>
    </div>
  )
}

function VenueOptionCard({ option, attendance, selected, onSelect }: {
  option: VenueOptionDto
  attendance: number
  selected: boolean
  onSelect: () => void
}) {
  const { venue } = option
  const capacity = venue.venueCapacity ?? 0
  const fill = Math.min(100, Math.round((attendance / Math.max(1, capacity)) * 100))
  const disabled = option.verdict === 'blocked'
  return (
    <button type="button" className="venue-option" data-verdict={option.verdict} aria-pressed={selected}
      disabled={disabled} onClick={onSelect}>
      <span className="venue-option__head">
        <span className="venue-option__name">{venue.venueAddress}</span>
        <span className="venue-option__verdict">{VERDICT_LABELS[option.verdict]}</span>
      </span>
      <span className="venue-option__capacity">
        <span className="venue-option__bar" aria-hidden="true"><span style={{ width: `${fill}%` }} /></span>
        <span>
          {attendance.toLocaleString()} of {capacity.toLocaleString()} seats
          {option.capacityOk ? ` · ${option.spareCapacity.toLocaleString()} spare` : ` · ${Math.abs(option.spareCapacity).toLocaleString()} short`}
        </span>
      </span>
      {option.reasons.length > 0 ? (
        <span className="venue-option__reasons">
          {option.reasons.map((r) => <span key={r}>{r}</span>)}
        </span>
      ) : null}
      {option.conflicts.map((c) => (
        <span key={c.startDatetime + c.eventName} className="venue-option__conflict">
          Booked: {c.eventName}, {timeRange(c.startDatetime, c.endDatetime)}
        </span>
      ))}
    </button>
  )
}

function Comparison({ event, option }: { event: EventDto; option: VenueOptionDto }) {
  const { venue } = option
  const needs = event.accessibilityNeeds.filter((n) => n !== 'none')
  return (
    <table className="venue-request__compare">
      <thead>
        <tr><th scope="col">Requirement</th><th scope="col">Event needs</th><th scope="col">{venue.venueAddress} offers</th></tr>
      </thead>
      <tbody>
        <tr data-ok={option.capacityOk}>
          <th scope="row">Capacity</th>
          <td>{event.expectedAttendance.toLocaleString()} people</td>
          <td>{(venue.venueCapacity ?? 0).toLocaleString()} {option.capacityOk ? '✓' : '✗'}</td>
        </tr>
        {needs.length === 0 ? (
          <tr data-ok="true"><th scope="row">Accessibility</th><td>None stated</td>
            <td>{venue.venueAccessibilities.map(accessibilityLabel).join(', ') || '—'}</td></tr>
        ) : needs.map((need) => {
          const ok = !option.missingAccessibility.includes(need)
          return (
            <tr key={need} data-ok={ok}>
              <th scope="row">Accessibility</th>
              <td>{accessibilityLabel(need)}</td>
              <td>{ok ? 'Provided ✓' : 'Not provided ✗'}</td>
            </tr>
          )
        })}
        <tr>
          <th scope="row">Layout &amp; facilities</th>
          <td>{event.venueRequirements}</td>
          <td>
            {venue.supportedLayouts.map((l) => venueLayoutLabels[l as VenueLayout] ?? l).join(', ')}
            {venue.venueFacilities.length ? <> · {venue.venueFacilities.map((f) => venueFacilityLabels[f as Facility] ?? f).join(', ')}</> : null}
          </td>
        </tr>
        <tr>
          <th scope="row">Opening hours</th>
          <td>{timeRange(event.startDatetime, event.endDatetime)}</td>
          <td>{venue.operatingInformation}</td>
        </tr>
      </tbody>
    </table>
  )
}
