import { useEffect, useState } from 'react'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RowLink } from '../../components/ui/RowLink'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { StatusFilter } from '../../components/ui/JourneyStatus'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
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
  // EC-NEW2: filter by the same status everyone sees.
  const [filter, setFilter] = useState<string | null>(null)

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
  const statuses = queue ? [...queue.needsReview, ...queue.awaitingOrganiser]
    .flatMap((r) => (r.journeyStatus ? [r.journeyStatus] : [])) : []
  const matches = (r: EventRequestDto) => filter === null || r.journeyStatus?.code === filter

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
          <StatusFilter statuses={statuses} selected={filter} onChange={setFilter} label="Filter the review queue by status" />
          <QueueSection
            title="Needs your review"
            hint="Submitted and assigned to you."
            requests={queue.needsReview.filter(matches)}
          />
          <QueueSection
            title="Waiting for the organiser"
            hint="Clarification was asked for; these come back to you when the organiser resubmits."
            requests={queue.awaitingOrganiser.filter(matches)}
          />
        </>
      ) : null}
    </div>
  )
}

function QueueSection({ title, hint, requests }: {
  title: string
  hint: string
  requests: EventRequestDto[]
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
            <RowLink to={`/coordinator/requests/${request.requestId}`} className="card dash-row-card" titleAs="h3"
              title={request.eventName || 'Untitled request'}
              badges={<StatusBadge status={request.status} journey={request.journeyStatus} />}>
              <p>{request.organisation}</p>
              <p>
                {request.expectedAttendance ?? '—'} attendees ·{' '}
                {request.startDatetime ? formatEventDateTime(request.startDatetime) : 'no date given'}
                {' · '}updated {formatRelativeTime(request.updatedAt)}
              </p>
            </RowLink>
          </li>
        ))}
      </ul>
    </section>
  )
}
