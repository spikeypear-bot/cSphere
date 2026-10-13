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
import { EVENT_STATUS_LABELS, type EventDto } from '../../types/event'
import { EventReadinessPanel } from './EventReadinessPanel'
import { OrganiserArrangements } from './OrganiserArrangements'
import { BOOKING_STATUS_LABELS, type EventVenueBookingDto } from '../../types/venueBookingRequest'
import { ReadinessChecklistPanel } from '../safetyOfficer/ReadinessChecklistPanel'
import type { ReadinessDto } from '../safetyOfficer/readinessTypes'
import { useSafetyData } from '../safetyOfficer/useSafetyData'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
import './EventDetailsPage.css'

/**
 * One page per event, shared by the Organiser and the Event Coordinator. Both
 * see the same status and explanation (EC-NEW2). The coordinator also gets
 * the EC08 lifecycle panel (send for safety review, withdraw, confirm) and a
 * read-only view of the Safety Officer's checklist (SO-C3); the organiser
 * gets the confirmed arrangements (EO04).
 */
export function EventDetailsPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const { role } = useSession()
  const location = useLocation()
  const flash = location.state as { justApproved?: boolean; bookingRequested?: string } | null
  const [event, setEvent] = useState<EventDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const readinessState = useSafetyData<ReadinessDto>(
    eventId && role === 'coordinator' ? `/events/${eventId}/readiness` : null,
  )
  // Bumped after a lifecycle action to re-trigger the fetch effect below —
  // same inline-fetch-effect shape as useEventRequestDraft.ts, rather than
  // calling a setState-ish function directly from inside the effect.
  const [reloadToken, setReloadToken] = useState(0)

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
        setError(err instanceof ApiClientError ? err.message : 'Could not load this event.')
      })
    return () => {
      cancelled = true
    }
  }, [eventId, reloadToken])

  if (error) return <p role="alert">{error}</p>
  if (!event) return <p>Loading…</p>

  const journey = event.journeyStatus ?? null
  // Planning-stage edits (venues, equipment) stay open while changes are
  // requested by the Safety Officer, matching EventStatus.isPlanningEditable.
  const planning = event.status === 'pending' || event.status === 'safety_changes_requested'

  return (
    <div className="page">
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

      {flash?.justApproved ? (
        <Notice>Request approved. The event is now in Planning and the organiser has been notified.</Notice>
      ) : null}
      {flash?.bookingRequested ? (
        <Notice>Booking request for {flash.bookingRequested} sent to Venue Staff for review.</Notice>
      ) : null}

      {role === 'coordinator' ? (
        <EventReadinessPanel eventId={event.eventId} onChanged={() => setReloadToken((n) => n + 1)} />
      ) : null}
      {role === 'organiser' ? <OrganiserArrangements key={reloadToken} eventId={event.eventId} /> : null}

      {role === 'coordinator' ? <VenueBookingCard eventId={event.eventId} planning={planning} /> : null}
      {role === 'coordinator' && planning ? (
        <Card className="event-details__venue">
          <h2>Equipment</h2>
          <p>Submit the equipment needs for Technical Support Staff to review and prepare.</p>
          <Link className="button button--primary" to={`/coordinator/events/${event.eventId}/equipment-request`}>
            Request equipment
          </Link>
        </Card>
      ) : null}

      {role === 'coordinator' ? (
        readinessState.error ? <p role="alert">Could not load readiness checklist. {readinessState.error}</p>
          : readinessState.data ? (
            <>
              <Card className="event-details__history">
                <h2>Safety Officer's review</h2>
                <p>Current approval: {readinessState.data.approvalStatus.replace('_', ' ')}</p>
                {readinessState.data.approvalNote ? <p>Note: {readinessState.data.approvalNote}</p> : null}
                {readinessState.data.reviewedBy ? (
                  <p>Reviewed by {readinessState.data.reviewedBy}
                    {readinessState.data.reviewedAt ? ` · ${new Date(readinessState.data.reviewedAt).toLocaleString()}` : ''}
                  </p>
                ) : null}
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

      <EventHistoryCard key={reloadToken} eventId={event.eventId} />
    </div>
  )
}

/** Loads one side panel's data independently of the event itself, so a
 * failure here never hides the event details. */
function useSideData<T>(path: string, isValid: (value: unknown) => boolean, reloadKey = 0) {
  const [data, setData] = useState<T | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let cancelled = false
    apiClient.get<unknown>(path)
      .then((value) => {
        if (cancelled) return
        if (isValid(value)) setData(value as T)
        else setFailed(true)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
    }
    // isValid is a stable module-level check at every call site.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, reloadKey])
  return { data, failed }
}

/** EC03 from the event page: the current booking request, or the way to make one. */
function VenueBookingCard({ eventId, planning }: { eventId: string; planning: boolean }) {
  const [reload, setReload] = useState(0)
  const { data: bookings, failed } = useSideData<EventVenueBookingDto[]>(
    `/events/${eventId}/venue-bookings`, Array.isArray, reload)
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (failed) return null
  if (!bookings) return null
  const active = bookings.find((b) => b.status === 'pending' || b.status === 'approved')
  const latestRejected = bookings.find((b) => b.status === 'rejected')

  async function cancelRequest(bookingId: string) {
    setBusy(true)
    setError(null)
    try {
      await apiClient.post(`/events/${eventId}/venue-bookings/${bookingId}/cancel`, { reason: reason.trim() || null })
      setCancelling(false)
      setReason('')
      setReload((n) => n + 1)
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
        <p>Alternative venue arrangements required. An existing booking is affected by venue unavailability.</p>
        <Link to={`/coordinator/events/${eventId}/venue-booking`}>Review bookings and arrange another venue</Link>
      </div>}
      {active ? (
        <>
          <p>
            <strong>{active.venueAddress}</strong>: {BOOKING_STATUS_LABELS[active.status]}
            {active.submittedAt ? <span className="field-hint"> · requested {new Date(active.submittedAt).toLocaleString()}</span> : null}
          </p>
          {active.status === 'pending' && !cancelling ? (
            <Button variant="secondary" onClick={() => setCancelling(true)}>Cancel this request</Button>
          ) : null}
          {active.status === 'pending' && cancelling ? (
            <div className="event-details__cancel">
              <label htmlFor="cancel-reason">Reason for Venue Staff (optional)</label>
              <textarea id="cancel-reason" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
              <div className="event-details__cancel-actions">
                <Button variant="secondary" onClick={() => { setCancelling(false); setReason('') }}>Keep request</Button>
                <Button disabled={busy} onClick={() => void cancelRequest(active.bookingId)}>
                  {busy ? 'Cancelling…' : 'Confirm cancellation'}
                </Button>
              </div>
            </div>
          ) : null}
          {error ? <p role="alert">{error}</p> : null}
        </>
      ) : (
        <>
          <p>No venue has been requested yet.</p>
          {latestRejected ? (
            <div className="field-hint">
              <p>{latestRejected.venueAddress} was rejected{latestRejected.rejectReason ? `: ${latestRejected.rejectReason}` : '.'}</p>
              {latestRejected.alternativeVenueAddress ? <p>Suggested alternative venue: {latestRejected.alternativeVenueAddress}</p> : null}
              {latestRejected.alternativeArrangement ? <p>Alternative arrangement: {latestRejected.alternativeArrangement}</p> : null}
            </div>
          ) : null}
          {planning ? (
            <Link className="button button--primary" to={`/coordinator/events/${eventId}/venue-booking`}>
              Request a venue
            </Link>
          ) : null}
        </>
      )}
    </Card>
  )
}

/** The event's whole journey from request to now (EC01/EC02/EO26/EC03),
 * filtered by the server to what this role may see. */
function EventHistoryCard({ eventId }: { eventId: string }) {
  const { data: entries, failed } = useSideData<ActivityDto[]>(`/events/${eventId}/timeline`, Array.isArray)
  if (failed || !entries) return null
  return (
    <Card className="event-details__history">
      <h2>History</h2>
      <ActivityTimeline entries={entries} emptyText="No history has been recorded for this event." />
    </Card>
  )
}
