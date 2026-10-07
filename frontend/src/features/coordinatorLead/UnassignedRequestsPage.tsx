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

/**
 * ECL-C1: the unassigned queue (Week 7 customer change 5). Every submitted
 * event request that no Event Coordinator has been assigned to, as overview
 * cards — enough to see what is waiting, not the full request. The server
 * sends them longest-waiting first and already leaves out drafts and anything
 * assigned or decided, so the page renders the list as given.
 *
 * View-only on purpose: assigning a coordinator is ELC-C6 and opening a
 * request for review is ECL-C3, so a card has no action yet.
 */
export function UnassignedRequestsPage() {
  // The same GET-on-mount hook the Venue Staff pages use (generic despite its
  // name). Keeping the previous data means a refresh, or a refresh that
  // fails, leaves the cards already on screen in place.
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
          {/* A pending request cannot be edited until a coordinator is
              assigned, so its last update is the moment it was submitted. */}
          <dt>Submitted</dt>
          <dd>{formatRelativeTime(request.updatedAt)}</dd>
        </div>
      </dl>
    </Card>
  )
}
