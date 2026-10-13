import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import './ReviewQueuePage.css'

/** Mirrors backend ReviewQueueDto (EC02). */
export interface ReviewQueue {
  needsReview: EventRequestDto[]
  awaitingOrganiser: EventRequestDto[]
}

/**
 * EC02 review queue, split the way a coordinator works through it: requests
 * waiting on *my* decision, and requests waiting on the *organiser* (EC01).
 * Unassigned requests and requests assigned to other coordinators are not
 * returned by the server at all: the Event Coordinator Lead assigns each
 * request (ELC-C6). Opening a request goes to its full review screen, where
 * decisions are made.
 */
export function ReviewQueuePage() {
  const [queue, setQueue] = useState<ReviewQueue | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    apiClient.get<ReviewQueue>('/event-requests/queue')
      .then((result) => {
        if (!cancelled) setQueue(result)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiClientError ? err.message : 'Could not load the review queue.')
      })
    return () => {
      cancelled = true
    }
  }, [])

  const total = queue ? queue.needsReview.length + queue.awaitingOrganiser.length : 0

  return (
    <div className="page">
      <PageHeader
        title="Event Request Review"
        description="Requests assigned to you, oldest first. Open one to review its details and history, then approve it, ask the organiser for clarification, or reject it."
      />

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
            hint="Clarification was asked for; these come back to you when the organiser resubmits."
            requests={queue.awaitingOrganiser}
            renderAction={(request) => (
              <Link className="button button--secondary" to={`/coordinator/requests/${request.requestId}`}>
                View
              </Link>
            )}
          />
        </>
      ) : null}
    </div>
  )
}

function QueueSection({ title, hint, requests, renderAction }: {
  title: string
  hint: string
  requests: EventRequestDto[]
  renderAction: (request: EventRequestDto) => ReactNode
}) {
  if (requests.length === 0) return null
  const headingId = `queue-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <section className="review-queue__section" aria-labelledby={headingId}>
      <h2 id={headingId}>
        {title} <span className="review-queue__count">{requests.length}</span>
      </h2>
      <p className="field-hint">{hint}</p>
      <ul className="plain-list">
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
            </Card>
          </li>
        ))}
      </ul>
    </section>
  )
}
