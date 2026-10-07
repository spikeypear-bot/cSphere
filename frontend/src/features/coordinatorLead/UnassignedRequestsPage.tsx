import { Link, useLocation } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Notice } from '../../components/ui/Notice'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import type { EventRequestDto } from '../../types/eventRequest'
import { useVenueRead } from '../venueStaff/useVenueRead'
import { LeadRequestCard } from './LeadRequestCard'
import './UnassignedRequestsPage.css'

/** ECL-C1: unassigned event requests as overview cards, each opening its
 * review page (ECL-C3). */
export function UnassignedRequestsPage() {
  // Shared GET-on-mount hook; keeps the cards on screen during a refresh.
  const { data: requests, error, loading, retry } = useVenueRead<EventRequestDto[]>('/event-requests/unassigned', true)
  const refreshing = loading && requests !== undefined
  // Set by the review page after the Lead rejects or sends a request back.
  const notice = (useLocation().state as { notice?: string } | null)?.notice

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

      {notice ? <Notice>{notice}</Notice> : null}
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
          <ul className="lead-request-cards">
            {requests.map((request) => (
              <li key={request.requestId}>
                {/* For a pending request, updatedAt is when it was submitted. */}
                <LeadRequestCard request={request} submittedAt={request.updatedAt} />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  )
}
