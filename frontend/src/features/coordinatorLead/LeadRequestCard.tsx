import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import './LeadRequestCard.css'

/** One event request as an overview card on the Lead's lists (ECL-C1,
 * ECL-C2), opening its review page (ECL-C3). */
export function LeadRequestCard({ request, submittedAt, coordinatorName, headingLevel = 2 }: {
  request: EventRequestDto
  /** When the organiser submitted the request. */
  submittedAt: string
  /** The assigned Event Coordinator, on a list of assigned requests. */
  coordinatorName?: string
  /** 3 where the cards sit under a group heading. */
  headingLevel?: 2 | 3
}) {
  const Heading = headingLevel === 3 ? 'h3' : 'h2'
  // An assigned request is the coordinator's to review; the Lead only views it.
  const action = coordinatorName ? 'View request' : 'Review request'
  return (
    <Card className="lead-request-card">
      <div className="lead-request-card__head">
        <Heading>{request.eventName?.trim() || 'Untitled request'}</Heading>
        <StatusBadge status={request.status} />
      </div>
      <p className="lead-request-card__organisation">{request.organisation}</p>
      <dl className="lead-request-card__facts">
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
          <dt>Submitted</dt>
          <dd>{formatRelativeTime(submittedAt)}</dd>
        </div>
        {coordinatorName ? (
          <div>
            <dt>Event Coordinator</dt>
            <dd>{coordinatorName}</dd>
          </div>
        ) : null}
      </dl>
      <Link className="button button--secondary lead-request-card__review"
        to={`/coordinator-lead/unassigned-requests/${request.requestId}`}
        state={coordinatorName ? { coordinator: { id: request.coordinatorId, name: coordinatorName } } : undefined}
        aria-label={`${action}: ${request.eventName?.trim() || 'Untitled request'}`}>
        {action}
      </Link>
    </Card>
  )
}
