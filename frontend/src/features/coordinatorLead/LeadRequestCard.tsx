import { RowLink } from '../../components/ui/RowLink'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import './LeadRequestCard.css'

/** One event request as an overview card on the Lead's lists (ECL-C1,
 * ECL-C2): the whole card opens its review page (ECL-C3). */
export function LeadRequestCard({ request, submittedAt, timeLabel = 'Submitted', coordinatorName, headingLevel = 2 }: {
  request: EventRequestDto
  /** When the organiser submitted the request. */
  submittedAt: string
  /** What that time is called, where it is not the submission time. */
  timeLabel?: string
  /** The assigned Event Coordinator, on a list of assigned requests. */
  coordinatorName?: string
  /** 3 where the cards sit under a group heading. */
  headingLevel?: 2 | 3
}) {
  // An assigned request is the coordinator's to review; the Lead views it and
  // can reassign it from there (ELC-C5). The review page reads this state to
  // lead back to the assignments and name the coordinator the card showed.
  const linkState = coordinatorName ? { coordinator: { id: request.coordinatorId, name: coordinatorName } } : undefined
  return (
    <RowLink to={`/coordinator-lead/unassigned-requests/${request.requestId}`} className="card dash-row-card"
      titleAs={headingLevel === 3 ? 'h3' : 'h2'} title={request.eventName?.trim() || 'Untitled request'}
      badges={<StatusBadge status={request.status} journey={request.journeyStatus} />}
      state={linkState}>
      <p>{request.organisation}</p>
      <dl className="dash-figures dash-facts">
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
          <dt>{timeLabel}</dt>
          <dd>{formatRelativeTime(submittedAt)}</dd>
        </div>
        {coordinatorName ? (
          <div>
            <dt>Event Coordinator</dt>
            <dd>{coordinatorName}</dd>
          </div>
        ) : null}
      </dl>
    </RowLink>
  )
}
