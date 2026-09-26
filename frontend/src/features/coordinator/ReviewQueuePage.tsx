import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import './ReviewQueuePage.css'

/** Mirrors backend ReviewQueueDto (EC02). */
export interface ReviewQueue {
  needsReview: EventRequestDto[]
  awaitingOrganiser: EventRequestDto[]
  unassigned: EventRequestDto[]
}

/**
 * EC02 review queue, split the way a coordinator works through it: requests
 * waiting on *my* decision, requests waiting on the *organiser* (EC01), and
 * unassigned ones I can pick up (Coordinator Assignment). Requests assigned
 * to other coordinators are not returned by the server at all. Opening a
 * request goes to its full review screen, where decisions are made.
 */
export function ReviewQueuePage() {
  const { userId } = useSession()
  const [queue, setQueue] = useState<ReviewQueue | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<Record<string, string>>({})

  const load = useCallback(async () => {
    try {
      setQueue(await apiClient.get<ReviewQueue>('/event-requests/queue'))
      setError(null)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not load the review queue.')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function assignToSelf(request: EventRequestDto) {
    setBusyId(request.requestId)
    setActionError((prev) => ({ ...prev, [request.requestId]: '' }))
    try {
      await apiClient.post(`/event-requests/${request.requestId}/assign-coordinator`, { coordinatorUserId: userId })
      await load()
    } catch (err) {
      setActionError((prev) => ({
        ...prev,
        [request.requestId]: err instanceof ApiClientError ? err.message : 'Could not assign this request.',
      }))
    } finally {
      setBusyId(null)
    }
  }

  const total = queue ? queue.needsReview.length + queue.awaitingOrganiser.length + queue.unassigned.length : 0

  return (
    <div className="review-queue">
      <h1>Event Request Review</h1>
      <p className="field-hint">
        Requests assigned to you, oldest first. Open one to review its details and history, then approve it,
        ask the organiser for clarification, or reject it.
      </p>

      {error ? <p role="alert">{error}</p> : null}
      {queue === null && !error ? <p>Loading…</p> : null}
      {queue && total === 0 ? (
        <Card className="review-queue__empty">
          <p>Nothing is waiting for review right now.</p>
        </Card>
      ) : null}

      {queue ? (
        <>
          <QueueSection
            title="Needs your review"
            hint="Submitted and assigned to you."
            requests={queue.needsReview}
            renderAction={(request) => (
              <Link className="button button--primary" to={`/coordinator/requests/${request.requestId}`}>
                Review
              </Link>
            )}
          />
          <QueueSection
            title="Waiting for the organiser"
            hint="You asked for clarification; these come back to you when the organiser resubmits."
            requests={queue.awaitingOrganiser}
            renderAction={(request) => (
              <Link className="button button--secondary" to={`/coordinator/requests/${request.requestId}`}>
                View
              </Link>
            )}
          />
          <QueueSection
            title="Unassigned"
            hint="Pick a request up to become its coordinator."
            requests={queue.unassigned}
            renderAction={(request) => (
              <Button variant="secondary" disabled={busyId === request.requestId}
                onClick={() => void assignToSelf(request)}>
                Assign to me
              </Button>
            )}
            errors={actionError}
          />
        </>
      ) : null}
    </div>
  )
}

function QueueSection({ title, hint, requests, renderAction, errors = {} }: {
  title: string
  hint: string
  requests: EventRequestDto[]
  renderAction: (request: EventRequestDto) => ReactNode
  errors?: Record<string, string>
}) {
  if (requests.length === 0) return null
  const headingId = `queue-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <section className="review-queue__section" aria-labelledby={headingId}>
      <h2 id={headingId}>
        {title} <span className="review-queue__count">{requests.length}</span>
      </h2>
      <p className="field-hint">{hint}</p>
      <ul className="review-queue__list">
        {requests.map((request) => (
          <li key={request.requestId}>
            <Card className="review-queue__row">
              <div className="review-queue__summary">
                <h3>{request.eventName || 'Untitled request'}</h3>
                <p className="field-hint">{request.organisation}</p>
                <p className="field-hint">
                  {request.expectedAttendance ?? '—'} attendees ·{' '}
                  {request.startDatetime ? new Date(request.startDatetime).toLocaleDateString() : 'no date given'}
                  {' · '}updated {formatRelativeTime(request.updatedAt)}
                </p>
              </div>
              <div className="review-queue__actions">
                <StatusBadge status={request.status} />
                {renderAction(request)}
              </div>
              {errors[request.requestId] ? (
                <p role="alert" className="review-queue__error">{errors[request.requestId]}</p>
              ) : null}
            </Card>
          </li>
        ))}
      </ul>
    </section>
  )
}
