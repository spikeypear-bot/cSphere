import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { EventDto } from '../../types/event'
import './EventDetailsPage.css'

const STATUS_LABEL: Record<string, string> = {
  pending: 'Awaiting confirmation',
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

      {canConfirm ? (
        <Card className="event-details__confirm">
          <p>Once venue and equipment arrangements are complete, confirm this event.</p>
          {confirmError ? <p role="alert">{confirmError}</p> : null}
          <Button onClick={() => void confirm()} disabled={confirming}>
            {confirming ? 'Confirming…' : 'Confirm event'}
          </Button>
        </Card>
      ) : null}
    </div>
  )
}
