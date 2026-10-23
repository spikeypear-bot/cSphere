import { useEffect, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import type { EventDto } from '../../types/event'
import type { EventRequestDto } from '../../types/eventRequest'
import {
  CANCELLABLE_EVENT_STATUSES,
  reasonProblem,
  type EventCancellationRequestDto,
} from '../../types/eventCancellation'

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString('en-SG', { dateStyle: 'medium', timeStyle: 'short' })
}

/** EO07 AC3: Planning to Confirmed; a Confirmed event whose end passed is Completed. */
function cancellable(event: EventDto): boolean {
  return (CANCELLABLE_EVENT_STATUSES as readonly string[]).includes(event.status)
    && event.journeyStatus?.code !== 'COMPLETED'
}

/**
 * EO07 on the event page. The organiser asks for the event to be cancelled,
 * with a reason; while that waits, they see their reason and when they asked
 * instead of the button (AC6). Nothing about the event changes until the
 * assigned coordinator decides (AC3).
 */
export function EventCancellationPanel({ event, role, onChanged }: {
  event: EventDto
  role: string | null
  onChanged: () => void
}) {
  const [requests, setRequests] = useState<EventCancellationRequestDto[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let stale = false
    apiClient.get<EventCancellationRequestDto[]>(`/events/${event.eventId}/cancellation-requests`)
      .then((result) => {
        if (stale) return
        setRequests(Array.isArray(result) ? result : [])
        setFailed(false)
      })
      .catch(() => {
        if (!stale) setFailed(true)
      })
    return () => {
      stale = true
    }
  }, [event.eventId, reload])

  if (failed || requests === null) return null
  const pending = requests.find((r) => r.status === 'pending') ?? null
  const latest = requests[0] ?? null
  const changed = () => {
    setReload((n) => n + 1)
    onChanged()
  }

  if (role === 'organiser') {
    return <OrganiserCancellation event={event} pending={pending} latest={latest} onRequested={changed} />
  }
  return null
}

function PendingSummary({ request }: { request: EventCancellationRequestDto }) {
  return (
    <>
      <p><strong>Reason:</strong> {request.reason}</p>
      <p className="field-hint">
        Requested {formatWhen(request.requestedAt)}{request.requesterName ? ` by ${request.requesterName}` : ''}
      </p>
    </>
  )
}

function OrganiserCancellation({ event, pending, latest, onRequested }: {
  event: EventDto
  pending: EventCancellationRequestDto | null
  latest: EventCancellationRequestDto | null
  onRequested: () => void
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (pending) {
    return (
      <Card className="event-details__venue" role="region" aria-labelledby="cancellation-heading">
        <h2 id="cancellation-heading">Cancellation requested</h2>
        <p role="status">Awaiting the coordinator's decision. The event stays as it is until then.</p>
        <PendingSummary request={pending} />
      </Card>
    )
  }
  if (!cancellable(event)) return null

  async function send() {
    const problem = reasonProblem(reason, 'Please give a reason for cancelling this event.')
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      // The cancellation request hangs off the organiser's original request.
      const own = await apiClient.get<EventRequestDto[]>('/event-requests')
      const origin = own.find((r) => r.eventId === event.eventId)
      if (!origin) throw new Error('missing request')
      await apiClient.post(`/event-requests/${origin.requestId}/cancellation-request`, { reason: reason.trim() })
      setOpen(false)
      setReason('')
      onRequested()
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message
        : 'The cancellation request could not be sent. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="event-details__venue" role="region" aria-labelledby="cancellation-heading">
      <h2 id="cancellation-heading">Cancel this event</h2>
      {latest?.status === 'declined' ? (
        <p>
          Your last cancellation request was declined
          {latest.decisionReason ? `: ${latest.decisionReason}` : '.'}
        </p>
      ) : null}
      {!open ? (
        <>
          <p>If the event will no longer take place, ask your coordinator to cancel it.</p>
          <Button variant="secondary" onClick={() => setOpen(true)}>Request cancellation</Button>
        </>
      ) : (
        <div className="event-details__cancel">
          <p>Your coordinator decides. Until then the event and its arrangements stay as they are.</p>
          <label htmlFor="event-cancellation-reason">Reason for cancelling</label>
          <textarea id="event-cancellation-reason" rows={3} value={reason}
            onChange={(e) => setReason(e.target.value)} />
          {error ? <p role="alert">{error}</p> : null}
          <div className="event-details__cancel-actions">
            <Button variant="secondary" disabled={busy}
              onClick={() => { setOpen(false); setReason(''); setError(null) }}>
              Keep event
            </Button>
            <Button disabled={busy} onClick={() => void send()}>
              {busy ? 'Sending…' : 'Send cancellation request'}
            </Button>
          </div>
        </div>
      )}
    </Card>
  )
}
