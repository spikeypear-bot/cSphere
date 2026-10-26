import { OperatingHours } from '../../components/OperatingHours'
import { BookingAssessment } from '../venueStaff/BookingAssessment'
import { useLiveRefresh } from '../../lib/useLiveRefresh'
import { VenueRequirementReviews } from '../venueStaff/VenueRequirementReviews'
import { HoldStatus } from '../venueStaff/HoldStatus'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { isNotAssigned } from '../../lib/notAssigned'
import { isPlanningEditable, type EventDto } from '../../types/event'
import { ACCESSIBILITY_LABELS, type AccessibilityFeature } from '../../types/eventRequest'
import { venueFacilityLabels, venueLayoutLabels, type Facility, type VenueLayout } from '../../types/venue'
import {
  BOOKING_STATUS_LABELS,
  type EventVenueBookingDto,
  type BookingRequirements,
  type VenueOptionDto,
  type VenueVerdict,
} from '../../types/venueBookingRequest'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './VenueBookingRequestPage.css'
import { validMinutes, occupiedPeriod } from '../../lib/bookingTiming'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'

type BookingInput = { start: string; end: string; setup: string; turnaround: string; reason: string; requirements?: BookingRequirements }
type TimingReview = { key: string; option?: VenueOptionDto; error?: string }
function singaporeInput(value: string) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Singapore', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)).replace(' ', 'T')
}
function instant(value: string) { return new Date(value + '+08:00').toISOString() }
function validInput(input: BookingInput | undefined): input is BookingInput {
  return !!input && (input.requirements?.expectedAttendance == null || (Number.isInteger(input.requirements.expectedAttendance) && input.requirements.expectedAttendance > 0)) && validMinutes(input.setup) && validMinutes(input.turnaround)
    && Number.isFinite(Date.parse(input.start + '+08:00'))
    && Date.parse(input.end + '+08:00') > Date.parse(input.start + '+08:00')
}
function timingKey(input: BookingInput) { return [input.start, input.end, input.setup, input.turnaround, JSON.stringify(input.requirements)].join('|') }
function changedTiming(input: BookingInput, option: VenueOptionDto, event: EventDto) {
  return !!input.requirements || input.start !== singaporeInput(event.startDatetime) || input.end !== singaporeInput(event.endDatetime)
    || Number(input.setup) !== (option.venue.setupMinutes ?? 0) || Number(input.turnaround) !== (option.venue.turnaroundMinutes ?? 0)
}

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

function unavailablePeriodLabel(period: NonNullable<VenueOptionDto['unavailablePeriods']>[number]) {
  const range = period.affectedFrom && period.affectedUntil
    ? ` · ${timeRange(period.affectedFrom, period.affectedUntil)}`
    : ''
  return `${period.description}${range}`
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
  const liveRevision = useLiveRefresh()
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [event, setEvent] = useState<EventDto | null>(null)
  const [options, setOptions] = useState<VenueOptionDto[] | null>(null)
  const [bookings, setBookings] = useState<EventVenueBookingDto[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notAssigned, setNotAssigned] = useState(false)
  const [search, setSearch] = useState('')
  const [showBlocked, setShowBlocked] = useState(false)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [bookingTimes, setBookingTimes] = useState<Record<string, BookingInput>>({})
  const [notes, setNotes] = useState('')
  const [justification, setJustification] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [timingReviews, setTimingReviews] = useState<Record<string, TimingReview>>({})
  const [retry, setRetry] = useState(0)
  const [withdrawReasons, setWithdrawReasons] = useState<Record<string,string>>({})

  useEffect(() => {
    if (!eventId || !event || !options) return
    let active = true
    const timer = setTimeout(() => {
      for (const id of selectedIds) {
        const input = bookingTimes[id]
        const option = options.find(o => o.venue.venueId === id)
        if (!option || !validInput(input) || !changedTiming(input, option, event)) continue
        const key = timingKey(input)
        const params = new URLSearchParams({ setupDurationMinutes: input.setup, turnaroundDurationMinutes: input.turnaround,
          startDatetime: instant(input.start), endDatetime: instant(input.end) })
        if(input.requirements?.expectedAttendance != null) params.set('expectedAttendance', String(input.requirements.expectedAttendance))
        if(input.requirements?.requiredFacilities != null) params.set('requiredFacilities', input.requirements.requiredFacilities.join(','))
        if(input.requirements?.accessibilityNeeds != null) params.set('accessibilityNeeds', input.requirements.accessibilityNeeds.join(','))
        apiClient.get<VenueOptionDto[]>('/events/' + eventId + '/venue-options?' + params).then(values => {
          if (active) setTimingReviews(current => ({ ...current, [id]: { key, option: values.find(o => o.venue.venueId === id) } }))
        }).catch((error: unknown) => {
          if (active) setTimingReviews(current => ({ ...current, [id]: { key, error: error instanceof Error ? error.message : 'Could not check occupied time.' } }))
        })
      }
    }, 250)
    return () => { active = false; clearTimeout(timer) }
  }, [eventId, event, options, selectedIds, bookingTimes, retry])

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    Promise.all([
      apiClient.get<EventDto>(`/events/${eventId}`),
      apiClient.get<VenueOptionDto[]>(`/events/${eventId}/venue-options`),
      apiClient.get<EventVenueBookingDto[]>(`/events/${eventId}/venue-bookings`),
    ]).then(([e, o, b]) => {
      if (cancelled) return
      setLoadError(null)
      setEvent(e)
      setOptions(o)
      setBookings(b)
    }).catch((err: unknown) => {
      if (cancelled) return
      if (isNotAssigned(err)) setNotAssigned(true)
      setLoadError(err instanceof ApiClientError
        ? err.isForbidden ? 'Only the coordinator assigned to this event can request a venue for it.' : err.message
        : 'Could not load this event.')
    })
    return () => {
      cancelled = true
    }
  }, [eventId, liveRevision])

  const activeBookings = (bookings ?? []).filter(b => b.status === 'pending' || b.status === 'approved' || b.status === 'attention')
  const requestedSourceId = searchParams.get('replacesBookingId')
  const unavailableSources = activeBookings.filter(booking => booking.requiresAlternative)
  const implicitUnavailableSource = unavailableSources.length === 1 ? unavailableSources[0] : null
  const replacementSource = requestedSourceId
    ? (bookings ?? []).find(booking => booking.bookingId === requestedSourceId) ?? null
    : implicitUnavailableSource
  const replacementPending = !!replacementSource && (bookings ?? []).some(booking =>
    booking.replacesBookingId === replacementSource.bookingId && booking.status === 'pending')
  const replacementApproved = !!replacementSource && (bookings ?? []).some(booking =>
    booking.replacesBookingId === replacementSource.bookingId && (booking.status === 'approved' || booking.status === 'attention'))
  const sourceCanBeReplaced = !!replacementSource && (
    replacementSource.status === 'approved' || replacementSource.status === 'attention' || (replacementSource.status === 'pending' && replacementSource.requiresAlternative)
    || replacementSource.status === 'rejected' || replacementSource.status === 'cancelled'
  ) && !replacementApproved
  const replacing = sourceCanBeReplaced ? replacementSource : null

  const requestableOptions = useMemo(() => {
    if (!replacing) return options ?? []
    const unavailableVenueIds = new Set((bookings ?? [])
      .filter(booking => (booking.status === 'pending' || booking.status === 'approved' || booking.status === 'attention')
        && booking.bookingId !== replacing.bookingId)
      .map(booking => booking.venueId))
    return (options ?? []).filter(option => !unavailableVenueIds.has(option.venue.venueId))
  }, [options, bookings, replacing])

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase()
    return requestableOptions.filter((o) => (showBlocked || o.verdict !== 'blocked')
      && (!term || `${o.venue.venueAddress} ${o.venue.additionalInformation ?? ''}`
        .toLowerCase().includes(term)))
  }, [requestableOptions, search, showBlocked])

  const counts = useMemo(() => {
    const c: Record<VenueVerdict, number> = { suitable: 0, needs_justification: 0, blocked: 0 }
    requestableOptions.forEach((o) => { c[o.verdict] += 1 })
    return c
  }, [requestableOptions])

  if (notAssigned) return <NotAssignedNotice what="event" />
  if (loadError && !event) {
    return (
      <div className="page page--compact venue-request">
        <Link to={eventId ? `/coordinator/events/${eventId}` : '/coordinator'}>Back to event</Link>
        <p role="alert">{loadError}</p>
      </div>
    )
  }
  if (!event || !options || !bookings) return <p>Loading venues…</p>

  const selected = options.filter((o) => selectedIds.includes(o.venue.venueId))
  const reviewed = (option: VenueOptionDto) => {
    const input = bookingTimes[option.venue.venueId]
    if (!validInput(input)) return undefined
    if (!changedTiming(input, option, event)) return option
    const review = timingReviews[option.venue.venueId]
    return review?.key === timingKey(input) ? review.option : undefined
  }
  const needsJustification = selected.some(o => reviewed(o)?.verdict === 'needs_justification')
  const notesTooLong = notes.trim().length > MAX_TEXT
  const justificationTooLong = justification.trim().length > MAX_TEXT
  const timesValid = selected.every((option) => {
    const times = bookingTimes[option.venue.venueId]
    return validInput(times) && times.reason.trim().length <= MAX_TEXT
      && ((Number(times.setup) === (option.venue.setupMinutes ?? 0) && Number(times.turnaround) === (option.venue.turnaroundMinutes ?? 0)) || !!times.reason.trim())
  })
  const canSubmit = selected.length > 0 && selected.every(o => !!reviewed(o) && reviewed(o)?.verdict !== 'blocked') && !notesTooLong && !justificationTooLong
    && timesValid && (!needsJustification || justification.trim().length > 0)

  async function submit() {
    if (!canSubmit || !eventId) return
    setSubmitting(true)
    setSubmitError(null)
    try {
      const requests = selected.map((option, index) => ({
          venueId: option.venue.venueId,
          requirements: bookingTimes[option.venue.venueId].requirements,
          bookingNotes: notes.trim() || null,
          suitabilityNote: reviewed(option)?.verdict === 'needs_justification' ? justification.trim() : null,
          startDatetime: instant(bookingTimes[option.venue.venueId].start),
          endDatetime: instant(bookingTimes[option.venue.venueId].end),
          setupDurationMinutes: Number(bookingTimes[option.venue.venueId].setup),
          turnaroundDurationMinutes: Number(bookingTimes[option.venue.venueId].turnaround),
          timingNote: bookingTimes[option.venue.venueId].reason.trim() || null,
          ...(replacing && index === 0 ? { replacesBookingId: replacing.bookingId } : {}),
        }))
      if (requests.length === 1) {
        await apiClient.post<EventVenueBookingDto>(`/events/${eventId}/venue-bookings`, requests[0])
      } else {
        await apiClient.post<EventVenueBookingDto[]>(`/events/${eventId}/venue-bookings/batch`, { bookings: requests })
      }
      navigate(`/coordinator/events/${eventId}`, {
        state: { bookingRequested: selected.map((option) => option.venue.venueAddress).join(', ') },
      })
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'The booking request could not be submitted. Nothing was sent.')
    } finally {
      setSubmitting(false)
    }
  }

  const blockedReason = loadError ? 'Could not refresh current venue details. Waiting for a successful refresh before submission.' : requestedSourceId && !sourceCanBeReplaced
    ? 'This booking is no longer eligible for a replacement. Refresh the event details.'
    : replacementPending
      ? 'This booking already has a replacement request pending. Wait for Venue Staff to review it.'
      : !isPlanningEditable(event.status) && !(['confirmed', 'safety_approved', 'awaiting_safety_review'].includes(event.status) && replacing)
        ? 'Venue booking requests can only be made while the event is in Planning, except for an eligible replacement.'
        : null

  return (
    <div className="page page--compact venue-request">
      {loadError && <p role="alert">Could not refresh venue details. {loadError}</p>}
      <Link to={`/coordinator/events/${event.eventId}`}>Back to event</Link>
      <PageHeader title={replacing ? 'Find a replacement venue' : 'Request a venue'}
        description={<p className="field-hint">for <strong>{event.eventName}</strong></p>} />
      {replacing && <section role="status" className="venue-request__replacement-callout">
        <span className="venue-request__replacement-icon" aria-hidden="true">↻</span>
        <div>
          <h2>Replacing {replacing.venueAddress}</h2>
          <p className="venue-request__replacement-reason">
            {replacing.requiresAlternative
              ? `Alternative arrangements required for ${replacing.venueAddress}.`
              : `Rebooking ${replacing.venueAddress}${replacing.rejectReason ? `: ${replacing.rejectReason}` : '.'}`}
          </p>
          <p>The original booking stays unchanged until Venue Staff approves a replacement.</p>
        </div>
      </section>}

      <div className="split-layout split-layout--aside-first">
        <Card className="venue-request__needs split-layout__sticky" aria-labelledby="needs-heading">
          <h2 id="needs-heading">What the event needs</h2>
          <dl>
            <div><dt>When</dt><dd>{timeRange(event.startDatetime, event.endDatetime)}</dd></div>
            <div><dt>Expected attendance</dt><dd>{event.expectedAttendance.toLocaleString()} people</dd></div>
            <div><dt>Venue requirements</dt><dd>{event.venueRequirements}</dd></div>
            <div><dt>Required facilities</dt><dd>{(event.requiredFacilities ?? []).map((f) => venueFacilityLabels[f]).join(', ') || 'None specified'}</dd></div>
            <div><dt>Accessibility</dt><dd>{event.accessibilityNeeds.map(accessibilityLabel).join(', ') || 'None stated'}</dd></div>
            {event.equipmentRequirements ? <div><dt>Equipment</dt><dd>{event.equipmentRequirements}</dd></div> : null}
          </dl>
          <p className="field-hint">Each selected venue has its own booking times, setup and turnaround. Times are in Singapore time. Advertised event times stay unchanged.</p>
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
              <div className="venue-request__results-heading">
                <div>
                  <h2>{replacing ? 'Choose a replacement venue' : 'Choose venues'}</h2>
                  <p>{replacing
                    ? 'Select one venue to replace this booking.'
                    : 'Select one or more venues to send to Venue Staff.'}</p>
                </div>
                <span className="venue-request__result-count">{visible.length} {visible.length === 1 ? 'venue' : 'venues'}</span>
              </div>

              {visible.length === 0 ? (
                <Card><p>No venues match. {counts.blocked > 0 && !showBlocked ? 'Try showing unavailable venues to see why.' : ''}</p></Card>
              ) : (
                <ul className="venue-request__options" aria-label="Venues ranked for this event">
                  {visible.map((option) => (
                    <li key={option.venue.venueId}>
                      <VenueOptionCard option={option} attendance={event.expectedAttendance}
                        selected={selectedIds.includes(option.venue.venueId)}
                        onSelect={() => {
                          setSelectedIds((current) => replacing
                            ? current.includes(option.venue.venueId) ? [] : [option.venue.venueId]
                            : current.includes(option.venue.venueId)
                              ? current.filter((id) => id !== option.venue.venueId)
                              : [...current, option.venue.venueId])
                          setBookingTimes((current) => current[option.venue.venueId] ? current : {
                            ...current,
                            [option.venue.venueId]: {
                              start: singaporeInput(event.startDatetime),
                              end: singaporeInput(event.endDatetime),
                              setup: String(option.venue.setupMinutes ?? 0), turnaround: String(option.venue.turnaroundMinutes ?? 0), reason: '', requirements: replacing?.requirements ?? undefined,
                            },
                          })
                          setSubmitError(null)
                        }} />
                    </li>
                  ))}
                </ul>
              )}

              {selected.length > 0 ? (
                <Card className="venue-request__review" aria-labelledby="compare-heading">
                  <h2 id="compare-heading">{replacing
                    ? 'Review replacement venue'
                    : `Review and submit ${selected.length} venue${selected.length === 1 ? '' : 's'}`}</h2>
                  <ul aria-label="Selected venues">
                    {selected.map((option) => (
                      <li key={option.venue.venueId}>
                        <strong>{option.venue.venueAddress}</strong>
                        <BookingTimingInputs option={option} input={bookingTimes[option.venue.venueId]} disabled={submitting}
                          onChange={input => setBookingTimes(current => ({ ...current, [option.venue.venueId]: input }))} />
                        <RoomRequirements input={bookingTimes[option.venue.venueId]} event={event}
                          onChange={requirements => setBookingTimes(current => ({...current,[option.venue.venueId]:{...current[option.venue.venueId],requirements}}))} />
                        {changedTiming(bookingTimes[option.venue.venueId], option, event) && validInput(bookingTimes[option.venue.venueId]) && !reviewed(option) && (
                          timingReviews[option.venue.venueId]?.key === timingKey(bookingTimes[option.venue.venueId])
                            ? <p role="alert">{timingReviews[option.venue.venueId]?.error ?? 'Could not check this venue.'} <Button onClick={() => setRetry(n => n + 1)}>Retry timing check</Button></p>
                            : <p role="status">Checking occupied time…</p>
                        )}
                        {reviewed(option) && <SuitabilitySummary option={reviewed(option)!} />}
                        {reviewed(option) && changedTiming(bookingTimes[option.venue.venueId], option, event) &&
                          <ul>{reviewed(option)!.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
                        <Button variant="secondary" className="venue-request__remove-button"
                          onClick={() => setSelectedIds((current) => current.filter((id) => id !== option.venue.venueId))}>
                          Remove selection
                        </Button>
                      </li>
                    ))}
                  </ul>
                  {selected.length === 1 ? <>
                    <VenueDetails venue={selected[0].venue} />
                    <Comparison event={event} option={reviewed(selected[0]) ?? selected[0]} input={bookingTimes[selected[0].venue.venueId]} /></> : null}

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

                  <p className="venue-request__submit-note">
                    This sends a <strong>pending</strong> request to Venue Staff. The venue is not reserved until they approve it.
                  </p>
                  {submitError ? <p role="alert" className="error-text">{submitError}</p> : null}
                  <Button className="venue-request__submit-button" disabled={!canSubmit || submitting} onClick={() => void submit()}>
                    {submitting ? 'Submitting…' : selected.length === 1
                      ? `${replacing ? 'Request replacement' : 'Request'} ${selected[0].venue.venueAddress}`
                      : `Request ${selected.length} venues`}
                  </Button>
                </Card>
              ) : null}
            </>
          )}

          <BookingAssessment eventId={eventId} />
      <VenueRequirementReviews key={event.eventId} eventId={event.eventId} />
          {bookings.length > 0 ? (
            <Card className="venue-request__history">
            <div className="venue-request__history-heading">
              <div>
                <h2>Earlier requests for this event</h2>
                <p>Previous requests for this event, including their review outcomes.</p>
              </div>
              <span className="venue-request__result-count">{bookings.length} {bookings.length === 1 ? 'request' : 'requests'}</span>
            </div>
            {submitError && <p role="alert">{submitError}</p>}
            <ul className="venue-request__history-list">
              {bookings.map((b) => (
                <li className="venue-request__history-item" key={b.bookingId}>
                  <div className="venue-request__history-item-heading">
                    <strong>{b.venueAddress ?? 'Unknown venue'}</strong>
                    <span className="venue-request__history-status" data-booking-status={b.status}>
                      {BOOKING_STATUS_LABELS[b.status]}
                    </span>
                  </div>
                  {b.requiresAlternative && <p className="venue-request__history-note">Alternative arrangements required</p>}
                  {b.replacesBookingId && <p className="venue-request__history-note">
                    Replacement for {bookings.find(source => source.bookingId === b.replacesBookingId)?.venueAddress ?? 'earlier booking'}
                  </p>}
                  {bookings.some(replacement => replacement.replacesBookingId === b.bookingId) && (
                    <p className="venue-request__history-note">Replacement request status: {bookings
                      .filter(replacement => replacement.replacesBookingId === b.bookingId)
                      .map(replacement => BOOKING_STATUS_LABELS[replacement.status]).join(', ')}</p>
                  )}
                  {(b.status === 'approved' || b.status === 'attention' || b.status === 'rejected' || b.requiresAlternative) &&
                    <Link to={`/coordinator/events/${eventId}/venue-booking?replacesBookingId=${b.bookingId}`}>Replace or reschedule this booking</Link>}
                  {b.requirementOutstanding && (b.status === 'rejected' || b.status === 'cancelled') && <div>
                    <p>This venue requirement still blocks event readiness.</p>
                    <label>Reason this venue is no longer required<input value={withdrawReasons[b.bookingId] ?? ''}
                      onChange={e => setWithdrawReasons(current => ({...current,[b.bookingId]:e.target.value}))} /></label>
                    <Button disabled={!withdrawReasons[b.bookingId]?.trim() || submitting} onClick={async () => {
                      setSubmitting(true);setSubmitError(null)
                      try {
                        const updated=await apiClient.post<EventVenueBookingDto>(`/events/${eventId}/venue-bookings/${b.bookingId}/withdraw-requirement`,{reason:withdrawReasons[b.bookingId]})
                        setBookings(current => current?.map(item => item.bookingId===updated.bookingId?updated:item) ?? null)
                      } catch(error) { setSubmitError(error instanceof Error?error.message:'Could not withdraw this requirement.') }
                      finally { setSubmitting(false) }
                    }}>Withdraw venue requirement</Button>
                  </div>}
                  {b.rejectReason ? <p className="venue-request__history-note"><strong>Reason:</strong> {b.rejectReason}</p> : null}
                  {b.alternativeVenueAddress ? <p className="venue-request__history-note"><strong>Suggested venue:</strong> {b.alternativeVenueAddress}</p> : null}
                  {b.alternativeArrangement ? <p className="venue-request__history-note"><strong>Alternative arrangement:</strong> {b.alternativeArrangement}</p> : null}
                  <div className="venue-request__history-note"><strong>Operating hours</strong><OperatingHours hours={b.operatingHours} /></div>
                  <BookingTimingSummary timing={b.timing} />
                  <HoldStatus holds={b.holds} />

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

function RoomRequirements({input,event,onChange}:{input:BookingInput;event:EventDto;onChange:(value:BookingRequirements|undefined)=>void}) {
  const req=input.requirements
  return <fieldset className="venue-request__timings"><legend>Requirements for this room</legend>
    <label>Expected attendees (leave blank to use event attendance: {event.expectedAttendance})
      <input type="number" min="1" step="1" value={req?.expectedAttendance ?? ''}
        onChange={e => onChange({...req,expectedAttendance:e.target.value===''?null:Number(e.target.value)})} /></label>
    <label><input type="checkbox" checked={req?.requiredFacilities != null}
      onChange={e => onChange({...req,requiredFacilities:e.target.checked?[]:null})} />Choose room-specific facilities</label>
    {req?.requiredFacilities != null && <div>{Object.entries(venueFacilityLabels).map(([key,label]) => <label key={key}>
      <input type="checkbox" checked={req.requiredFacilities!.includes(key)} onChange={e => onChange({...req,requiredFacilities:e.target.checked?[...req.requiredFacilities!,key]:req.requiredFacilities!.filter(v=>v!==key)})} />{label}</label>)}</div>}
    <label><input type="checkbox" checked={req?.accessibilityNeeds != null}
      onChange={e => onChange({...req,accessibilityNeeds:e.target.checked?[]:null})} />Choose room-specific accessibility needs</label>
    {req?.accessibilityNeeds != null && <div>{Object.entries(ACCESSIBILITY_LABELS).filter(([key])=>key!=='none').map(([key,label]) => <label key={key}>
      <input type="checkbox" checked={req.accessibilityNeeds!.includes(key)} onChange={e => onChange({...req,accessibilityNeeds:e.target.checked?[...req.accessibilityNeeds!,key]:req.accessibilityNeeds!.filter(v=>v!==key)})} />{label}</label>)}</div>}
    <label>Room requirements (optional)<textarea maxLength={MAX_TEXT} value={req?.venueRequirements ?? ''}
      onChange={e => onChange({...req,venueRequirements:e.target.value || null})} /></label>
    {req && <Button variant="secondary" onClick={()=>onChange(undefined)}>Use event requirements</Button>}
  </fieldset>
}

function BookingTimingInputs({ option, input, disabled, onChange }: {
  option: VenueOptionDto; input: BookingInput; disabled: boolean; onChange: (input: BookingInput) => void
}) {
  const defaultSetup = option.venue.setupMinutes ?? 0
  const defaultTurnaround = option.venue.turnaroundMinutes ?? 0
  const custom = Number(input.setup) !== (option.venue.setupMinutes ?? 0)
    || Number(input.turnaround) !== (option.venue.turnaroundMinutes ?? 0)
  return <fieldset className="venue-request__timings" disabled={disabled} aria-label={'Timings for ' + option.venue.venueAddress}>
    <legend>Booking times <span>(Singapore time)</span></legend>
    <p className="venue-request__timings-intro">
      Event start and end stay unchanged. Setup reserves time before the event; turnaround reserves time after it.
      Together, these buffers extend the time the venue is occupied.
    </p>
    <div className="venue-request__window-fields">
      <label>Start<input type="datetime-local" value={input.start} onChange={e => onChange({ ...input, start: e.target.value })} /></label>
      <label>End<input type="datetime-local" value={input.end} onChange={e => onChange({ ...input, end: e.target.value })} /></label>
    </div>
    <div className="venue-request__buffer-fields">
      <label>
        Setup (minutes)
        <input type="number" min="0" max="10080" step="1" value={input.setup}
          onChange={e => onChange({ ...input, setup: e.target.value })} />
        <span>Venue preparation before the event. Venue default: {defaultSetup} minutes.</span>
      </label>
      <label>
        Turnaround (minutes)
        <input type="number" min="0" max="10080" step="1" value={input.turnaround}
          onChange={e => onChange({ ...input, turnaround: e.target.value })} />
        <span>Reset or clean the venue after the event. Venue default: {defaultTurnaround} minutes.</span>
      </label>
    </div>
    {custom && <label className="venue-request__timing-note">Reason for different timings (required)
      <textarea maxLength={MAX_TEXT} value={input.reason} onChange={e => onChange({ ...input, reason: e.target.value })} />
    </label>}
    {!validInput(input) && <p role="alert">Enter an end after the start and whole durations from 0 to 10,080 minutes.</p>}
    {validInput(input) && <BookingTimingSummary timing={{ startDatetime: instant(input.start), endDatetime: instant(input.end),
      setupDurationMinutes: Number(input.setup), turnaroundDurationMinutes: Number(input.turnaround),
      ...occupiedPeriod(instant(input.start), instant(input.end), Number(input.setup), Number(input.turnaround)),
      timingNote: input.reason || null, conflicts: [] }} />}
  </fieldset>
}

function VenueDetails({ venue }: { venue: VenueOptionDto['venue'] }) {
  return (
    <section className="venue-request__venue-details" aria-labelledby="venue-details-heading">
      <h3 id="venue-details-heading">Selected venue details</h3>
      <p className="venue-request__venue-address">{venue.venueAddress}</p>
      <dl>
        <div><dt>Capacity</dt><dd>{venue.venueCapacity?.toLocaleString() ?? 'Not recorded'} people</dd></div>
        <div><dt>Supported layouts</dt><dd>{venue.supportedLayouts.map((l) => venueLayoutLabels[l as VenueLayout] ?? l).join(', ') || 'None recorded'}</dd></div>
        <div><dt>Facilities</dt><dd>{venue.venueFacilities.map((f) => venueFacilityLabels[f as Facility] ?? f).join(', ') || 'None recorded'}</dd></div>
        <div><dt>Accessibility</dt><dd>{venue.venueAccessibilities.map(accessibilityLabel).join(', ') || 'None recorded'}</dd></div>
        <div><dt>Operating hours</dt><dd><OperatingHours hours={venue.operatingHours} /></dd></div>
        {venue.additionalInformation ? <div><dt>Additional information</dt><dd>{venue.additionalInformation}</dd></div> : null}
      </dl>
    </section>
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
  const disabled = false
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
        <span key={c.startDatetime + c.endDatetime} className="venue-option__conflict">
          {c.description}, {timeRange(c.startDatetime, c.endDatetime)}
        </span>
      ))}
      {(option.unavailablePeriods ?? []).map((period) => (
        <span key={period.description + period.affectedFrom} className="venue-option__conflict">
          Unavailable: {unavailablePeriodLabel(period)}
        </span>
      ))}
    </button>
  )
}

function SuitabilitySummary({ option }: { option: VenueOptionDto }) {
  const missingFacilities = option.missingFacilities ?? []
  const capacityOk = option.capacityOk
  const availabilityOk = option.conflicts.length === 0
  const facilitiesOk = missingFacilities.length === 0
  const unavailable = option.unavailablePeriods ?? []
  const canProceed = capacityOk && availabilityOk && facilitiesOk && unavailable.length === 0
    && option.verdict === 'suitable'
  const headline = canProceed
    ? `${option.venue.venueAddress} meets this event's venue requirements`
    : `${option.venue.venueAddress} cannot be booked yet`
  const details = [
    capacityOk ? 'meets capacity' : 'does not meet capacity',
    facilitiesOk ? 'meets facility needs' : `lacks ${missingFacilities.length} required ${missingFacilities.length === 1 ? 'facility' : 'facilities'}`,
    availabilityOk && unavailable.length === 0 ? 'is available for the requested period'
      : unavailable.length > 0 ? 'is unavailable due to an operational issue' : 'has an overlapping approved booking',
  ]
  return (
    <div className="venue-request__summary" data-ready={canProceed}>
      <div className="venue-request__summary-icon" aria-hidden="true">{canProceed ? '✓' : '!'}</div>
      <div>
        <strong>{headline}</strong>
        <p>It {details.join(', ')}.</p>
        {!canProceed && option.verdict === 'needs_justification'
          ? <p className="field-hint">Accessibility is incomplete; add a justification for Venue Staff before submitting.</p>
          : null}
      </div>
      <span className="venue-request__summary-status">{canProceed ? 'Ready to request' : 'Review required'}</span>
    </div>
  )
}

function Comparison({ event, option, input }: { event: EventDto; option: VenueOptionDto; input: BookingInput }) {
  const period = validInput(input) ? occupiedPeriod(instant(input.start), instant(input.end), Number(input.setup), Number(input.turnaround)) : null
  const { venue } = option
  const needs = (input.requirements?.accessibilityNeeds ?? event.accessibilityNeeds).filter((n) => n !== 'none')
  return (
    <table className="venue-request__compare">
      <thead>
        <tr><th scope="col">Requirement</th><th scope="col">Event needs</th><th scope="col">{venue.venueAddress} offers</th></tr>
      </thead>
      <tbody>
        <tr data-ok={option.capacityOk}>
          <th scope="row">Capacity</th>
          <td>{(input.requirements?.expectedAttendance ?? event.expectedAttendance).toLocaleString()} people</td>
          <td>{(venue.venueCapacity ?? 0).toLocaleString()} {option.capacityOk ? '✓' : '✗'}</td>
        </tr>
        <tr data-ok={(option.unavailablePeriods ?? []).length === 0 && option.conflicts.length === 0}>
          <th scope="row">Availability</th>
          <td>{period ? timeRange(period.effectiveStart, period.effectiveEnd) : 'Enter valid booking times'}</td>
          <td>{(option.unavailablePeriods ?? []).length
            ? `Unavailable: ${(option.unavailablePeriods ?? []).map(unavailablePeriodLabel).join(', ')} ✗`
            : option.conflicts.length
              ? 'Overlaps an approved booking ✗'
              : 'Available ✓'}</td>
        </tr>
        <tr data-ok={(option.missingFacilities ?? []).length === 0}>
          <th scope="row">Required facilities</th>
          <td>{(input.requirements?.requiredFacilities ?? event.requiredFacilities ?? []).length
            ? (input.requirements?.requiredFacilities ?? event.requiredFacilities ?? []).map((f) => venueFacilityLabels[f as Facility]).join(', ')
            : 'None specified'}</td>
          <td>{(option.missingFacilities ?? []).length
            ? `Missing: ${(option.missingFacilities ?? []).map((f) => venueFacilityLabels[f as Facility] ?? f).join(', ')} ✗`
            : 'All provided ✓'}</td>
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
          <td>{input.requirements?.venueRequirements ?? event.venueRequirements}</td>
          <td>
            {venue.supportedLayouts.map((l) => venueLayoutLabels[l as VenueLayout] ?? l).join(', ')}
            {venue.venueFacilities.length ? <> · {venue.venueFacilities.map((f) => venueFacilityLabels[f as Facility] ?? f).join(', ')}</> : null}
          </td>
        </tr>
        <tr>
          <th scope="row">Opening hours</th>
          <td>{period ? timeRange(period.effectiveStart, period.effectiveEnd) : 'Enter valid booking times'}</td>
          <td><OperatingHours hours={venue.operatingHours} /></td>
        </tr>
      </tbody>
    </table>
  )
}
