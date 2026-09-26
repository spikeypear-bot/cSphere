import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { ActivityDto } from '../../types/activity'
import type { EventDto } from '../../types/event'
import { BOOKING_STATUS_LABELS, type EventVenueBookingDto } from '../../types/venueBookingRequest'
import './EventDetailsPage.css'

// 'pending' is shown as Planning: Week 4 'Event Status Management' names
// "planning" as the stage between approval and confirmation, and that is
// what an approved event is doing (EC02 "Proceed to Planning").
const STATUS_LABEL: Record<string, string> = {
  pending: 'Planning',
  confirmed: 'Confirmed',
  cancelled: 'Cancelled',
  completed: 'Completed',
}

/**
 * EO09's "can view the confirmed event arrangements" — the notification's
 * own deep link lands here. Shared between the Organiser (read-only) and the
 * Event Coordinator (who also gets the Confirm action) rather than two
 * near-identical pages, since the only real difference is one button.
 */
export function EventDetailsPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const { role } = useSession()
  const location = useLocation()
  const flash = location.state as { justApproved?: boolean; bookingRequested?: string } | null
  const [event, setEvent] = useState<EventDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [confirmError, setConfirmError] = useState<string | null>(null)
  // Bumped after a successful confirm to re-trigger the fetch effect below —
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

  async function confirm() {
    if (!eventId) return
    setConfirming(true)
    setConfirmError(null)
    try {
      await apiClient.post<EventDto>(`/events/${eventId}/confirm`)
      setReloadToken((n) => n + 1)
    } catch (err) {
      setConfirmError(err instanceof ApiClientError ? err.message : 'Could not confirm this event.')
    } finally {
      setConfirming(false)
    }
  }

  if (error) return <p role="alert">{error}</p>
  if (!event) return <p>Loading…</p>

  const canConfirm = role === 'coordinator' && event.status === 'pending'

  return (
    <div className="event-details">
      <div className="event-details__header">
        <h1>{event.eventName}</h1>
        <span className={`status-badge status-badge--${event.status === 'pending' ? 'pending' : 'approved'}`}>
          {STATUS_LABEL[event.status] ?? event.status}
        </span>
      </div>

      <Card className="event-details__card">
        <dl className="event-details__grid">
          <div>
            <dt>Purpose</dt>
            <dd>{event.purpose}</dd>
          </div>
          <div>
            <dt>Start</dt>
            <dd>{new Date(event.startDatetime).toLocaleString()}</dd>
          </div>
          <div>
            <dt>End</dt>
            <dd>{new Date(event.endDatetime).toLocaleString()}</dd>
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
        <p className="event-details__flash" role="status">
          Request approved. The event is now in Planning and the organiser has been notified.
        </p>
      ) : null}
      {flash?.bookingRequested ? (
        <p className="event-details__flash" role="status">
          Booking request for {flash.bookingRequested} sent to Venue Staff for review.
        </p>
      ) : null}

      {role === 'coordinator' ? <VenueBookingCard eventId={event.eventId} planning={event.status === 'pending'} /> : null}

      {canConfirm ? (
        <Card className="event-details__confirm">
          <p>Once venue and equipment arrangements are complete, confirm this event.</p>
          {confirmError ? <p role="alert">{confirmError}</p> : null}
          <Button onClick={() => void confirm()} disabled={confirming}>
            {confirming ? 'Confirming…' : 'Confirm event'}
          </Button>
        </Card>
      ) : null}

      <EventHistoryCard eventId={event.eventId} />
    </div>
  )
}

/** Loads one side panel's data independently of the event itself, so a
 * failure here never hides the event details. */
function useSideData<T>(path: string, isValid: (value: unknown) => boolean) {
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
  }, [path])
  return { data, failed }
}

/** EC03 from the event page: the current booking request, or the way to make one. */
function VenueBookingCard({ eventId, planning }: { eventId: string; planning: boolean }) {
  const { data: bookings, failed } = useSideData<EventVenueBookingDto[]>(`/events/${eventId}/venue-bookings`, Array.isArray)
  if (failed) return null
  if (!bookings) return null
  const active = bookings.find((b) => b.status === 'pending' || b.status === 'confirmed')
  const latestRejected = bookings.find((b) => b.status === 'rejected')
  return (
    <Card className="event-details__venue">
      <h2>Venue</h2>
      {active ? (
        <p>
          <strong>{active.venueAddress}</strong>: {BOOKING_STATUS_LABELS[active.status]}
          {active.submittedAt ? <span className="field-hint"> · requested {new Date(active.submittedAt).toLocaleString()}</span> : null}
        </p>
      ) : (
        <>
          <p>No venue has been requested yet.</p>
          {latestRejected ? (
            <p className="field-hint">
              {latestRejected.venueAddress} was rejected{latestRejected.rejectReason ? `: ${latestRejected.rejectReason}` : '.'}
            </p>
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
