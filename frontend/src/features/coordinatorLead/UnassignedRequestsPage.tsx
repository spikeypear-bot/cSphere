import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { useVenueRead } from '../venueStaff/useVenueRead'
import './UnassignedRequestsPage.css'

/** ECL-C1: unassigned event requests as overview cards, each opening its
 * review page (ECL-C3). */
export function UnassignedRequestsPage() {
  // Shared GET-on-mount hook; keeps the cards on screen during a refresh.
  const { data: requests, error, loading, retry } = useVenueRead<EventRequestDto[]>('/event-requests/unassigned', true)
  const refreshing = loading && requests !== undefined

  return (
    <div className="page">
      <PageHeader
        eyebrow={<Link to="/coordinator-lead">Event Coordinator Lead console</Link>}
        title="Unassigned requests"
        description="Submitted event requests that no Event Coordinator has been assigned to yet, longest-waiting first."
        actions={
          <Button variant="ghost" onClick={retry} disabled={loading} aria-label="Refresh unassigned requests">
            <RefreshIcon />{refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      {error ? <p role="alert">Could not load unassigned requests. {error}</p> : null}
      {loading && requests === undefined ? <p role="status">Loading unassigned requests…</p> : null}
      {requests && requests.length === 0 ? (
        <Card className="unassigned-requests__empty">
          <p>No unassigned requests right now. Newly submitted requests will appear here.</p>
        </Card>
      ) : null}

      {requests && requests.length > 0 ? (
        <>
          <p className="field-hint" role="status">
            {requests.length === 1 ? '1 request is' : `${requests.length} requests are`} waiting for a coordinator.
          </p>
          <ul className="unassigned-requests__grid">
            {requests.map((request) => (
              <li key={request.requestId}>
                <UnassignedRequestCard request={request} />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  )
}

function UnassignedRequestCard({ request }: { request: EventRequestDto }) {
  return (
    <Card className="unassigned-requests__card">
      <div className="unassigned-requests__card-head">
        <h2>{request.eventName?.trim() || 'Untitled request'}</h2>
        <StatusBadge status={request.status} />
      </div>
      <p className="unassigned-requests__organisation">{request.organisation}</p>
      <dl className="unassigned-requests__facts">
        <div>
          <dt>When (Singapore time)</dt>
          <dd>
            {request.startDatetime && request.endDatetime
              ? formatEventTimeRange(request.startDatetime, request.endDatetime)
              : 'No date given'}
          </dd>
        </div>
        <div>
          <dt>Expected attendance</dt>
          <dd>{request.expectedAttendance != null ? `${request.expectedAttendance.toLocaleString()} people` : 'Not given'}</dd>
        </div>
        <div>
          {/* For a pending request, updatedAt is when it was submitted. */}
          <dt>Submitted</dt>
          <dd>{formatRelativeTime(request.updatedAt)}</dd>
        </div>
      </dl>
      <Link className="button button--secondary unassigned-requests__review"
        to={`/coordinator-lead/unassigned-requests/${request.requestId}`}
        aria-label={`Review request: ${request.eventName?.trim() || 'Untitled request'}`}>
        Review request
      </Link>
    </Card>
  )
}
