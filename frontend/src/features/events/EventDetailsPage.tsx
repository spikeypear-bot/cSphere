import { OperatingHours } from '../../components/OperatingHours'
import { BookingAssessment } from '../venueStaff/BookingAssessment'
import { useLiveRefresh } from '../../lib/useLiveRefresh'
import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { JourneyStatusBadge, JourneyStatusExplainer, JourneyStepper } from '../../components/ui/JourneyStatus'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { ActivityDto } from '../../types/activity'
import { EVENT_STATUS_LABELS, isPlanningEditable, type EventDto } from '../../types/event'
import { EventReadinessPanel } from './EventReadinessPanel'
import { EventCancellationPanel } from './EventCancellationPanel'
import { OrganiserArrangements } from './OrganiserArrangements'
import { OrganiserRegistrations } from './OrganiserRegistrations'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { isNotAssigned } from '../../lib/notAssigned'
import { BOOKING_STATUS_LABELS, type EventVenueBookingDto } from '../../types/venueBookingRequest'
import { ReadinessChecklistPanel } from '../safetyOfficer/ReadinessChecklistPanel'
import { SafetyDecisionSummary } from '../safetyOfficer/SafetyDecisionSummary'
import type { ReadinessDto } from '../safetyOfficer/readinessTypes'
import { useSafetyData } from '../safetyOfficer/useSafetyData'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './EventDetailsPage.css'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'
import { ChangeRequestPanel } from '../changeRequests/ChangeRequestPanel'
import { EquipmentDamagePanel } from './EquipmentDamagePanel'

/**
 * One page per event, shared by the Organiser and the Event Coordinator. Both
 * see the same status and explanation (EC-NEW2). The coordinator also gets
 * the EC08 lifecycle panel (send for safety review, withdraw, confirm) and a
 * read-only view of the Safety Officer's checklist (SO-C3); the organiser
 * gets the confirmed arrangements (EO04).
 */
export function EventDetailsPage() {
  const liveRevision = useLiveRefresh()
  const { eventId } = useParams<{ eventId: string }>()
  const { role } = useSession()
  const location = useLocation()
  const flash = location.state as { justApproved?: boolean; bookingRequested?: string } | null
  const [event, setEvent] = useState<EventDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  // ECL-C7: a coordinator opening an event that is not (or no longer) theirs.
  const [notAssigned, setNotAssigned] = useState(false)
  const readinessState = useSafetyData<ReadinessDto>(
    eventId && role === 'coordinator' ? `/events/${eventId}/readiness` : null,
  )
  // Bumped after a lifecycle action to re-trigger the fetch effect below —
  // same inline-fetch-effect shape as useEventRequestDraft.ts, rather than
  // calling a setState-ish function directly from inside the effect.
  const [reloadToken, setReloadToken] = useState(0)
  // Only external booking changes reset the panel; its own actions retain feedback.
  const [bookingReloadToken, setBookingReloadToken] = useState(0)

  useEffect(() => {
    if (!eventId) return
    let cancelled = false
    apiClient
      .get<EventDto>(`/events/${eventId}`)
      .then((result) => {
        if (cancelled) return
        setEvent(result)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (role === 'coordinator' && isNotAssigned(err)) setNotAssigned(true)
        setError(err instanceof ApiClientError ? err.message : 'Could not load this event.')
      })
    return () => {
      cancelled = true
    }
  }, [eventId, reloadToken, role, liveRevision])

  if (notAssigned) return <NotAssignedNotice what="event" />
  if (error && !event) return <p role="alert">{error}</p>
  if (!event) return <p>Loading…</p>

  const journey = event.journeyStatus ?? null
  // Planning-stage edits (venues, equipment) stay open while changes are
  // requested by the Safety Officer, matching EventStatus.isPlanningEditable.
  const planning = isPlanningEditable(event.status)

  return (
    <div className="page">
      {error && <p role="alert">Could not refresh event details. {error}</p>}
      <PageHeader
        title={event.eventName}
        actions={journey ? <JourneyStatusBadge status={journey} /> : (
          <span className={`status-badge status-badge--${event.status === 'pending' ? 'pending' : 'approved'}`}>
            {EVENT_STATUS_LABELS[event.status] ?? event.status}
          </span>
        )}
      />

      {journey ? (
        <div className="event-details__journey">
          <JourneyStepper status={journey} />
          <JourneyStatusExplainer status={journey} />
        </div>
      ) : null}

      <Card className="event-details__card">
        <dl className="event-details__grid">
          <div>
            <dt>Purpose</dt>
            <dd>{event.purpose}</dd>
          </div>
          <div>
            <dt>Start (Singapore time)</dt>
            <dd>{formatEventDateTime(event.startDatetime)}</dd>
          </div>
          <div>
            <dt>End (Singapore time)</dt>
            <dd>{formatEventDateTime(event.endDatetime)}</dd>
          </div>
          <div>
            <dt>Expected attendance</dt>
            <dd>{event.expectedAttendance}</dd>
          </div>
          <div>
            <dt>Venue requirements</dt>
            <dd>{event.venueRequirements}</dd>
          </div>
          {event.equipmentRequirements ? (
            <div>
              <dt>Equipment requirements</dt>
              <dd>{event.equipmentRequirements}</dd>
            </div>
          ) : null}
          <div>
            <dt>Event Coordinator</dt>
            <dd>
              {event.coordinatorName
                ? `${event.coordinatorName}${event.coordinatorEmail ? ` (${event.coordinatorEmail})` : ''}`
                : 'Not yet assigned'}
            </dd>
          </div>
        </dl>
      </Card>

      <ChangeRequestPanel eventId={event.eventId} journeyCode={journey?.code} />

      {flash?.justApproved ? (
        <Notice>Request approved. The event is now in Planning and the organiser has been notified.</Notice>
      ) : null}
      {flash?.bookingRequested ? (
        <Notice>Booking request for {flash.bookingRequested} sent to Venue Staff for review.</Notice>
      ) : null}

      {role === 'coordinator' ? (
        <EventReadinessPanel key={`readiness-${bookingReloadToken}`} eventId={event.eventId} onChanged={() => setReloadToken((n) => n + 1)} />
      ) : null}
      {role === 'organiser' ? <OrganiserArrangements key={`arrangements-${reloadToken}`} eventId={event.eventId} /> : null}
      {role === 'organiser' ? <OrganiserRegistrations key={`registrations-${reloadToken}`} eventId={event.eventId} /> : null}
      {/* EO07: the organiser asks for cancellation; the assigned coordinator decides. */}
      <EventCancellationPanel event={event} role={role} onChanged={() => setReloadToken((n) => n + 1)} />

      {role === 'coordinator' ? <VenueBookingCard eventId={event.eventId} planning={planning}
        active={event.status !== 'completed' && event.status !== 'cancelled'}
        canRebook={planning || event.status === 'confirmed'}
        onCancelled={() => { setBookingReloadToken(n => n + 1); setReloadToken(n => n + 1); readinessState.reload() }} /> : null}
      {/* EC-NEW3 (D36): revision is reachable for any non-cancelled, non-completed event, not just Planning. */}
      {role === 'coordinator' && event.status !== 'completed' && event.status !== 'cancelled' ? (
        <Card className="event-details__venue">
          <h2>Equipment</h2>
          <p>Submit the equipment needs for Technical Support Staff to review and prepare, or revise an existing request.</p>
          <Link className="button button--primary" to={`/coordinator/events/${event.eventId}/equipment-request`}>
            {planning ? 'Request equipment' : 'Manage equipment request'}
          </Link>
        </Card>
      ) : null}
      {/* TS09: damaged equipment the event relied on; the Lead reads it only. */}
      {role === 'coordinator' || role === 'coordinator-lead' ? (
        <EquipmentDamagePanel eventId={event.eventId} readOnly={role !== 'coordinator'} />
      ) : null}

      {role === 'coordinator' ? (
        readinessState.error ? <p role="alert">Could not load readiness checklist. {readinessState.error}</p>
          : readinessState.data ? (
            <>
              <Card className="event-details__history">
                <h2>Safety Officer's review</h2>
                <SafetyDecisionSummary readiness={readinessState.data} />
              </Card>
              <ReadinessChecklistPanel
                readiness={readinessState.data}
                editable={false}
                savingItem={null}
                onSave={async () => undefined}
              />
            </>
          ) : readinessState.loading ? <p role="status">Loading readiness checklist…</p> : null
      ) : null}

      {role === 'coordinator' && <BookingAssessment eventId={event.eventId} />}
      <EventHistoryCard key={`history-${reloadToken}`} eventId={event.eventId} />
    </div>
  )
}

/** Loads one side panel's data independently of the event itself, so a
 * failure here never hides the event details. */
function useSideData<T>(path: string, isValid: (value: unknown) => boolean, reloadKey = 0) {
  const liveRevision = useLiveRefresh()
  const [data, setData] = useState<T | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let cancelled = false
    apiClient.get<unknown>(path)
      .then((value) => {
        if (cancelled) return
        // A retry that succeeds clears an earlier failure (EC-NEW5 Try again).
        if (isValid(value)) {
          setData(value as T)
          setFailed(false)
        } else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
    // isValid is a stable module-level check at every call site.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, reloadKey, liveRevision])
  return { data, failed }
}

/** EC03 from the event page: the current booking request, or the way to make one. */
function VenueBookingCard({ eventId, planning, active, canRebook, onCancelled }: {
  eventId: string; planning: boolean; active: boolean; canRebook: boolean; onCancelled: () => void
}) {
  const [reload, setReload] = useState(0)
  const { data: bookings, failed } = useSideData<EventVenueBookingDto[]>(
    `/events/${eventId}/venue-bookings`, Array.isArray, reload)
  const [cancelling, setCancelling] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (failed) return null
  if (!bookings) return null
  const activeBookings = bookings.filter((b) => b.status === 'pending' || b.status === 'approved' || b.status === 'attention')
  const historicalBookings = bookings.filter((b) => b.status === 'rejected' || b.status === 'cancelled')
  const hasResolvedReplacement = (bookingId: string) => bookings.some((booking) =>
    booking.replacesBookingId === bookingId && (booking.status === 'pending' || booking.status === 'approved' || booking.status === 'attention'))
  const replacementLink = (booking: EventVenueBookingDto) =>
    `/coordinator/events/${eventId}/venue-booking?replacesBookingId=${encodeURIComponent(booking.bookingId)}`

  async function cancelRequest(bookingId: string) {
    setBusy(true)
    setError(null)
    try {
      await apiClient.post(`/events/${eventId}/venue-bookings/${bookingId}/cancel`, { reason: reason.trim() || null })
      setCancelling(null)
      setReason('')
      setReload((n) => n + 1)
      onCancelled()
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'The request could not be cancelled.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Card className="event-details__venue">
      <h2>Venue</h2>
      {bookings.some(b => b.requiresAlternative) && <div role="status" className="unavailability-warning">
        <p>Alternative venue arrangements required. Choose a replacement from the affected booking below.</p>
      </div>}
      {activeBookings.length > 0 ? (
        <>
          <ul className="event-details__booking-list" aria-label="Venue bookings">
            {activeBookings.map((booking) => (
              <li key={booking.bookingId}>
                <p>
                  <strong>{booking.venueAddress}</strong>: {BOOKING_STATUS_LABELS[booking.status]}
                  <BookingRequestMeta booking={booking} />
                </p>
                <dl className="venue-card-preview__information"><div><dt>Operating hours</dt><dd><OperatingHours hours={booking.operatingHours} /></dd></div></dl>
                <BookingTimingSummary timing={booking.timing} />
                {booking.requiresAlternative && canRebook && !hasResolvedReplacement(booking.bookingId) ? (
                  <Link className="button button--secondary" to={replacementLink(booking)}>Find replacement venue</Link>
                ) : null}
                {active && !cancelling && !bookings.some(b => b.status === 'pending' && b.replacesBookingId === booking.bookingId) ? (
                  <Button variant="secondary" onClick={() => setCancelling(booking.bookingId)}>
                    {booking.status === 'approved' ? 'Cancel this booking' : 'Cancel this request'}
                  </Button>
                ) : null}
                {cancelling === booking.bookingId ? (
                  <div className="event-details__cancel">
                    {booking.status === 'approved' && <p>Cancelling releases this venue and its setup and turnaround time. The event may return to Planning for review.</p>}
                    <label htmlFor={`cancel-reason-${booking.bookingId}`}>Reason for Venue Staff (optional)</label>
                    <textarea id={`cancel-reason-${booking.bookingId}`} rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
                    <div className="event-details__cancel-actions">
                      <Button variant="secondary" disabled={busy} onClick={() => { setCancelling(null); setReason('') }}>Keep request</Button>
                      <Button disabled={busy} onClick={() => void cancelRequest(booking.bookingId)}>
                        {busy ? 'Cancelling…' : 'Confirm cancellation'}
                      </Button>
                    </div>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
          {error ? <p role="alert">{error}</p> : null}
        </>
      ) : (
        <>
          <p>{bookings.length ? 'No active venue bookings.' : 'No venue has been requested yet.'}</p>
          {planning ? (
            <Link className="button button--primary" to={`/coordinator/events/${eventId}/venue-booking`}>
              Request a venue
            </Link>
          ) : null}
        </>
      )}
      {historicalBookings.length > 0 ? (
        <div className="event-details__earlier-requests">
          <h3>Earlier venue requests</h3>
          <ul className="event-details__booking-list" aria-label="Earlier venue requests">
            {historicalBookings.map((booking) => {
              const canReplace = !hasResolvedReplacement(booking.bookingId)
              const replacements = bookings.filter((candidate) => candidate.replacesBookingId === booking.bookingId)
              return (
                <li className="event-details__booking-item" key={booking.bookingId}>
                  <div className="event-details__booking-heading">
                    <p><strong>{booking.venueAddress}</strong></p>
                    <span className="event-details__booking-status" data-booking-status={booking.status}>
                      {BOOKING_STATUS_LABELS[booking.status]}
                    </span>
                  </div>
                  <BookingRequestMeta booking={booking} />
                  {booking.rejectReason ? <p className="event-details__booking-note"><strong>Reason:</strong> {booking.rejectReason}</p> : null}
                  {booking.alternativeVenueAddress ? <p className="event-details__booking-note"><strong>Suggested venue:</strong> {booking.alternativeVenueAddress}</p> : null}
                  {booking.alternativeArrangement ? <p className="event-details__booking-note"><strong>Alternative arrangement:</strong> {booking.alternativeArrangement}</p> : null}
                  {replacements.length > 0 ? (
                    <ul className="event-details__replacement-list" aria-label={`Replacement requests for ${booking.venueAddress}`}>
                      {replacements.map((replacement) => (
                        <li className="event-details__replacement-item" key={replacement.bookingId}>
                          <div className="event-details__booking-heading">
                            <p>Replacement attempt</p>
                            <span className="event-details__booking-status" data-booking-status={replacement.status}>
                              {BOOKING_STATUS_LABELS[replacement.status]}
                            </span>
                          </div>
                          <BookingRequestMeta booking={replacement} />
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  {canRebook && canReplace ? (
                    <Link className="button button--secondary event-details__rebook-button" to={replacementLink(booking)}>
                      Rebook this venue
                    </Link>
                  ) : null}
                </li>
              )
            })}
          </ul>
        </div>
      ) : null}
      {bookings.some(b => b.status === 'changed') && <div>
        <h3>Released bookings</h3>
        <ul>{bookings.filter(b => b.status === 'changed').map(b =>
          <li key={b.bookingId}>{b.venueAddress}: {BOOKING_STATUS_LABELS[b.status]}</li>)}</ul>
      </div>}
    </Card>
  )
}

function BookingRequestMeta({ booking }: { booking: EventVenueBookingDto }) {
  if (!booking.startDatetime && !booking.endDatetime && !booking.submittedAt) return null
  return (
    <span className="event-details__booking-meta">
      {booking.startDatetime && booking.endDatetime
        ? <span>{new Date(booking.startDatetime).toLocaleString()} – {new Date(booking.endDatetime).toLocaleString()}</span>
        : null}
      {booking.startDatetime && booking.endDatetime && booking.submittedAt ? <span aria-hidden="true">·</span> : null}
      {booking.submittedAt ? <span>Requested {new Date(booking.submittedAt).toLocaleString()}</span> : null}
    </span>
  )
}

/** EC-NEW5: the event's change log, its whole journey from request to now,
 * filtered by the server to what this role may see. If it cannot load, the
 * rest of the event page still works and the log can be retried. */
/** The event's whole journey from request to now (EC01/EC02/EO26/EC03),
 * filtered by the server to what this role may see. */
/** EC-NEW5: the event's change log, its whole journey from request to now,
 * filtered by the server to what this role may see. If it cannot load, the
 * rest of the event page still works and the log can be retried. */
function EventHistoryCard({ eventId }: { eventId: string }) {
  const [attempt, setAttempt] = useState(0)
  const { data: entries, failed } = useSideData<ActivityDto[]>(`/events/${eventId}/timeline`, Array.isArray, attempt)
  return (
    <Card className="event-details__history" role="region" aria-labelledby="change-log-heading">
      <h2 id="change-log-heading">Change log</h2>
      {failed ? (
        <div role="alert" className="event-details__log-error">
          <p>The change log could not be loaded.</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      ) : !entries ? (
        <p role="status" className="field-hint">Loading the change log…</p>
      ) : (
        <ActivityTimeline entries={entries} emptyText="No changes recorded yet." controls />
      )}
    </Card>
  )
}
