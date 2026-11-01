import { PageHeader } from '../../components/ui/PageHeader'
import { JourneyStatusBadge } from '../../components/ui/JourneyStatus'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import type { ChangeRequestView } from '../../types/changeRequest'
import type { EventDto } from '../../types/event'
import type { CoordinatorVenueOperationalIssueDto } from '../../types/venueOperationalIssue'
import { countOf, greetingFor } from '../technicalSupport/shared/format'
import { formatEventDateTime, formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import type { ReviewQueue } from './ReviewQueuePage'

/** How many rows each panel shows before "View all". */
const TOP = 5
const read = <T,>(path: string) => () => apiClient.get<T>(path, { cache: 'no-store' })

/** EC09: the coordinator console's front page — what needs their attention,
 * from the same reads the full pages use. */
export function CoordinatorHomePage() {
  const { username } = useSession()
  const queue = useResource(read<ReviewQueue>('/event-requests/queue'), 'queue')
  const events = useResource(read<EventDto[]>('/events/assigned'), 'events')
  const changes = useResource(read<ChangeRequestView[]>('/change-requests'), 'changes')
  const issues = useResource(read<CoordinatorVenueOperationalIssueDto[]>('/coordinator/operational-issues'), 'issues')
  const greeting = greetingFor()

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(queue, changes)}</p>}
      />
      <div className="dash-grid">
        <Panel title="Requests to review" action="View all" actionName="View all requests to review"
          to="/coordinator/review-queue" className="dash-grid__wide">
          <ResourceView resource={queue} loadingText="Loading requests…" errorText="The review queue could not be loaded."
            emptyText="No requests waiting for your review." emptyClassName="dash-empty" isEmpty={(q) => q.needsReview.length === 0}>
            {(q) => (
              <ul className="dash-queue" aria-label="Requests to review">
                {q.needsReview.slice(0, TOP).map((request) => (
                  <li key={request.requestId}>
                    <RowLink to={`/coordinator/requests/${request.requestId}`} className="dash-queue__row"
                      title={request.eventName ?? 'Untitled request'}>
                      <p className="dash-queue__meta">
                        {request.startDatetime && request.endDatetime
                          ? <span className="dash-queue__when">{formatEventTimeRange(request.startDatetime, request.endDatetime)}</span>
                          : null}
                        <span>{request.organisation}</span>
                      </p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Upcoming events" action="View all" actionName="View all my events" to="/coordinator/my-events">
          <ResourceView resource={events} loadingText="Loading your events…" errorText="Your events could not be loaded."
            emptyText="No events are assigned to you right now." emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Upcoming events">
                {list.slice(0, TOP).map((event) => (
                  <li key={event.eventId}>
                    <RowLink to={`/coordinator/events/${event.eventId}`} className="dash-queue__row" title={event.eventName}
                      badges={event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : null}>
                      <p className="dash-queue__when">{formatEventTimeRange(event.startDatetime, event.endDatetime)}</p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Change requests waiting" action="View events" actionName="View my events" to="/coordinator/my-events">
          <ResourceView resource={changes} loadingText="Loading change requests…" errorText="Change requests could not be loaded."
            emptyText="No change requests waiting." emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Change requests waiting">
                {list.slice(0, TOP).map((change) => (
                  <li key={change.changeRequestId}>
                    <RowLink to={`/coordinator/change-requests/${change.changeRequestId}`} className="dash-queue__row"
                      title={`Change to ${change.eventName}`}>
                      <p>Requested by {change.requestedByName} · {formatEventDateTime(change.requestedAt)}</p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Venue issues" action="View all" actionName="View all venue issues" to="/coordinator/operational-issues">
          <ResourceView resource={issues} loadingText="Loading venue issues…" errorText="Venue issues could not be loaded."
            emptyText="No venue issues affect your events." emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Venue issues">
                {list.slice(0, TOP).map((issue) => (
                  <li key={issue.issueId}>
                    <RowLink to="/coordinator/operational-issues" className="dash-queue__row" title={issue.venueAddress}>
                      <p>{issue.description}</p>
                      {issue.overlappingEvents.length > 0
                        ? <p>Affects {issue.overlappingEvents.map((event) => event.eventName).join(', ')}</p>
                        : null}
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>
      </div>
    </section>
  )
}

/** "3 requests to review · 1 change waiting", from whichever has loaded. */
function summarise(queue: Resource<ReviewQueue>, changes: Resource<ChangeRequestView[]>): string {
  if (queue.loading && changes.loading) return 'Checking what needs your attention…'
  const parts = [
    queue.data ? `${countOf(queue.data.needsReview.length, 'request')} to review` : null,
    changes.data ? `${countOf(changes.data.length, 'change')} waiting` : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : 'Some of what needs your attention could not be loaded.'
}
