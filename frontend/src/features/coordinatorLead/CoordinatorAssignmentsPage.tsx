import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import type { CoordinatorAssignmentsDto } from '../../types/eventRequest'
import { useVenueRead } from '../venueStaff/useVenueRead'
import { LeadRequestCard } from './LeadRequestCard'
import './CoordinatorAssignmentsPage.css'

/** ECL-C2: every Event Coordinator with the requests they hold that are
 * still under review, in the server's order (coordinators by name, each
 * one's requests soonest event first). View-only: each card opens the
 * request's review page (ECL-C3). */
export function CoordinatorAssignmentsPage() {
  // Shared GET-on-mount hook; keeps the cards on screen during a refresh.
  const { data: coordinators, error, loading, retry } =
    useVenueRead<CoordinatorAssignmentsDto[]>('/event-requests/assigned', true)
  const refreshing = loading && coordinators !== undefined
  const total = coordinators?.reduce((sum, coordinator) => sum + coordinator.requests.length, 0)

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator-lead">Event Coordinator Lead console</Link>}
        title="Coordinator assignments"
        description="Event requests under review and the Event Coordinator handling each, soonest event first."
        actions={
          <Button variant="ghost" onClick={retry} disabled={loading} aria-label="Refresh coordinator assignments">
            <RefreshIcon />{refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

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
              {coordinator.requests.map(({ request, submittedAt }) => (
                <li key={request.requestId}>
                  <LeadRequestCard request={request} submittedAt={submittedAt}
                    coordinatorName={coordinator.coordinatorName} headingLevel={3} />
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ))}
    </div>
  )
}
