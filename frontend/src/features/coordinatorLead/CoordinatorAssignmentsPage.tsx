import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { JourneyStatusBadge, StatusFilter } from '../../components/ui/JourneyStatus'
import type { CoordinatorAssignmentsDto } from '../../types/eventRequest'
import type { CoordinatorEventsDto } from '../../types/event'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import type { StatusView } from '../../types/status'
import { useVenueRead } from '../venueStaff/useVenueRead'
import { LeadRequestCard } from './LeadRequestCard'
import './CoordinatorAssignmentsPage.css'

/** ECL-C2: every Event Coordinator with the requests they hold that are
 * still under review, in the server's order (coordinators by name, each
 * one's requests soonest event first). Each card opens the request's
 * review page (ECL-C3), where the Lead can reassign it (ELC-C5).
 * ECL-C7: each coordinator's active events are listed too, so the Lead sees
 * every assignment and can view or reassign an event. */
export function CoordinatorAssignmentsPage() {
  // The outcome of a reassignment made on the review page.
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  // Shared GET-on-mount hook; keeps the cards on screen during a refresh.
  const { data: coordinators, error, loading, retry } =
    useVenueRead<CoordinatorAssignmentsDto[]>('/event-requests/assigned', true)
  // ECL-C7: the events each coordinator holds (a separate, Lead-only read).
  const { data: eventHolders, retry: retryEvents } = useVenueRead<CoordinatorEventsDto[]>('/events/by-coordinator', true)
  const eventsOf = (coordinatorId: string) =>
    eventHolders?.find((holder) => holder.coordinatorId === coordinatorId)?.events ?? []
  const refreshing = loading && coordinators !== undefined
  const total = coordinators?.reduce((sum, coordinator) => sum + coordinator.requests.length, 0)
  // EC-NEW2: filter by the same status everyone sees.
  const [filter, setFilter] = useState<string | null>(null)
  const statuses = [
    ...(coordinators ?? []).flatMap((c) => c.requests.flatMap(({ request }) =>
      request.journeyStatus ? [request.journeyStatus] : [])),
    ...(eventHolders ?? []).flatMap((h) => h.events.flatMap(({ event }) =>
      event.journeyStatus ? [event.journeyStatus] : [])),
  ]
  const matches = (status?: StatusView | null) => filter === null || status?.code === filter

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator-lead">Event Coordinator Lead console</Link>}
        title="Coordinator assignments"
        description="Every Event Coordinator with the requests under review and the active events they hold, soonest first."
        actions={
          <Button variant="ghost" onClick={() => { retry(); retryEvents() }} disabled={loading}
            aria-label="Refresh coordinator assignments">
            <RefreshIcon />{refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {notice ? <Notice>{notice}</Notice> : null}
      {error ? <p role="alert">Could not load coordinator assignments. {error}</p> : null}
      {loading && coordinators === undefined ? <p role="status">Loading coordinator assignments…</p> : null}
      {coordinators && total === 0 ? (
        <Card className="coordinator-assignments__empty">
          <p>No assigned requests right now. A request appears here once an Event Coordinator is assigned to it.</p>
        </Card>
      ) : null}
      {coordinators && total ? (
        <p className="field-hint" role="status">
          {total === 1 ? '1 request is' : `${total} requests are`} assigned and under review.
        </p>
      ) : null}
      <StatusFilter statuses={statuses} selected={filter} onChange={setFilter} label="Filter assigned requests by status" />

      {/* Coordinators who hold nothing keep their heading, so the Lead sees who is free. */}
      {coordinators?.map((coordinator) => (
        <section key={coordinator.coordinatorId} className="coordinator-assignments__group">
          <h2>
            {coordinator.coordinatorName}{' '}
            <span className="coordinator-assignments__count">
              {coordinator.requests.length === 1 ? '1 request' : `${coordinator.requests.length} requests`}
            </span>
          </h2>
          {coordinator.requests.length > 0 ? (
            <ul className="lead-request-cards">
              {coordinator.requests.filter(({ request }) => matches(request.journeyStatus)).map(({ request, submittedAt }) => (
                <li key={request.requestId}>
                  <LeadRequestCard request={request} submittedAt={submittedAt}
                    coordinatorName={coordinator.coordinatorName} headingLevel={3} />
                </li>
              ))}
            </ul>
          ) : null}
          {eventsOf(coordinator.coordinatorId).length > 0 ? (
            <p className="coordinator-assignments__events-count">
              {eventsOf(coordinator.coordinatorId).length === 1 ? 'Holding 1 active event'
                : `Holding ${eventsOf(coordinator.coordinatorId).length} active events`}
            </p>
          ) : null}
          {eventsOf(coordinator.coordinatorId).length > 0 ? (
            <ul className="lead-request-cards" aria-label={`Events held by ${coordinator.coordinatorName}`}>
              {eventsOf(coordinator.coordinatorId).filter(({ event }) => matches(event.journeyStatus)).map(({ requestId, event }) => (
                <li key={event.eventId}>
                  <Card className="lead-event-card">
                    <div className="lead-event-card__head">
                      <h3>{event.eventName}</h3>
                      {event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : null}
                    </div>
                    <p className="field-hint">{event.organisation} · {formatEventTimeRange(event.startDatetime, event.endDatetime)}</p>
                    <div className="lead-event-card__actions">
                      <Link className="button button--secondary" to={`/coordinator-lead/events/${event.eventId}`}
                        aria-label={`View event: ${event.eventName}`}>View event</Link>
                      {requestId ? (
                        <Link className="button button--ghost" to={`/coordinator-lead/unassigned-requests/${requestId}`}
                          state={{ coordinator: { id: coordinator.coordinatorId, name: coordinator.coordinatorName } }}
                          aria-label={`Reassign: ${event.eventName}`}>Reassign</Link>
                      ) : null}
                    </div>
                  </Card>
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ))}
    </div>
  )
}
