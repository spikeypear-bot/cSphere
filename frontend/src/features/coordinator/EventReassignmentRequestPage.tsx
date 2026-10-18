import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { EventDto } from '../../types/event'
import type { EventReassignmentRequestDto } from '../../types/eventReassignment'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { useVenueRead } from '../venueStaff/useVenueRead'
import './EventReassignmentRequestPage.css'

const MAX_REASON_LENGTH = 2000

export function EventReassignmentRequestPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const { userId, username } = useSession()
  const eventRead = useVenueRead<EventDto>(`/events/${eventId ?? ''}`)
  const requestRead = useVenueRead<EventReassignmentRequestDto[]>(`/events/${eventId ?? ''}/reassignment-requests`)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const event = eventRead.data ?? null
  const request = requestRead.data?.[0] ?? null
  const loading = eventRead.loading || requestRead.loading
  const error = actionError ?? eventRead.error ?? requestRead.error

  async function submit(eventForm: FormEvent<HTMLFormElement>) {
    eventForm.preventDefault()
    if (!eventId) return
    setBusy(true)
    setActionError(null)
    setNotice(null)
    try {
      const saved = await apiClient.post<EventReassignmentRequestDto>(
        `/events/${eventId}/reassignment-requests`, { reason: reason.trim() })
      requestRead.replaceData([saved])
      setNotice('Your reassignment request has been sent to the Event Coordinator Lead.')
    } catch (err) {
      setActionError(err instanceof ApiClientError ? err.message : 'Could not submit the reassignment request.')
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <p role="status">Loading event and reassignment status…</p>
  if (error && !event) {
    return <div className="page page--compact"><Link to="/coordinator/my-events">Back to my events</Link><p role="alert">{error}</p></div>
  }
  if (!event) return null

  const eventIsActive = event.status !== 'cancelled' && event.status !== 'completed'
  const canRequestAgain = event.coordinatorName === username

  return (
    <div className="page page--compact">
      <PageHeader
        eyebrow={<Link to={`/coordinator/events/${event.eventId}`}>Back to event</Link>}
        title="Request event reassignment"
        description={`Ask the Event Coordinator Lead to transfer "${event.eventName}" to another coordinator.`}
        actions={
          <Button variant="ghost" onClick={() => {
            eventRead.retry()
            requestRead.retry()
          }} disabled={loading}>
            Refresh status
          </Button>
        }
      />
      {error ? <Notice tone="danger">{error}</Notice> : null}
      {notice ? <Notice>{notice}</Notice> : null}
      <Card className="reassignment-card">
        <div className="reassignment-card__header">
          <h2>{event.eventName}</h2>
        </div>
        <dl className="reassignment-facts">
          <div><dt>Organisation</dt><dd>{event.organisation ?? '—'}</dd></div>
          <div><dt>When</dt><dd>{formatEventTimeRange(event.startDatetime, event.endDatetime)}</dd></div>
          <div><dt>Current coordinator</dt><dd>{event.coordinatorName ?? 'You'}</dd></div>
        </dl>
      </Card>
      {request ? (
        <Card className="reassignment-card" aria-labelledby="reassignment-status-title">
          <div className="reassignment-card__header">
            <h2 id="reassignment-status-title">
              Request status: <span className={`status-badge ${badgeClass(request.status)}`}>{statusLabel(request.status)}</span>
            </h2>
          </div>
          <blockquote className="reassignment-quote">
            <span className="reassignment-quote__label">Your reason</span>
            <p>{request.reason}</p>
          </blockquote>
          {request.decisionReason ? (
            <blockquote className="reassignment-quote reassignment-quote--response">
              <span className="reassignment-quote__label">Response from the Lead</span>
              <p>{request.decisionReason}</p>
            </blockquote>
          ) : null}
          {request.status === 'pending' ? (
            <Notice tone="info">The Lead is reviewing your request. You can continue planning this event while it is pending.</Notice>
          ) : null}
        </Card>
      ) : null}
      {eventIsActive && !requestRead.error && (!request || (request.status === 'declined'
        && request.requesterUserId === userId && canRequestAgain)) ? (
        <Card className="reassignment-card">
          <form className="reassignment-form" onSubmit={submit}>
            <div className="reassignment-card__header">
              <h2>{request ? 'Send a new request' : 'Why do you need to hand this over?'}</h2>
            </div>
            <p className="reassignment-form__intro">
              The Lead sees your reason when choosing who takes over. Planning carries on as normal until they decide.
            </p>
            <div className="field">
              <label htmlFor="reassignment-reason">Reason for reassignment</label>
              <textarea
                id="reassignment-reason"
                required
                maxLength={MAX_REASON_LENGTH}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="For example: I will be unavailable on the event date."
                rows={5}
              />
              <span className="field-hint reassignment-form__count">{reason.length}/{MAX_REASON_LENGTH}</span>
            </div>
            <div className="reassignment-form__actions">
              <Button type="submit" disabled={busy || reason.trim().length === 0}>
                {busy ? 'Sending…' : 'Send request'}
              </Button>
            </div>
          </form>
        </Card>
      ) : !eventIsActive ? (
        <Notice tone="info">This event is completed or cancelled and cannot be reassigned.</Notice>
      ) : null}
    </div>
  )
}

function statusLabel(status: EventReassignmentRequestDto['status']) {
  if (status === 'pending') return 'Pending'
  if (status === 'approved') return 'Approved'
  return 'Declined'
}

function badgeClass(status: EventReassignmentRequestDto['status']) {
  if (status === 'pending') return 'status-badge--pending'
  if (status === 'approved') return 'status-badge--approved'
  return 'status-badge--rejected'
}
