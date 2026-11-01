import { Link } from 'react-router-dom'
import { PageHeader } from '../../components/ui/PageHeader'
import { JourneyStatusBadge } from '../../components/ui/JourneyStatus'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import { countOf, greetingFor } from '../../lib/greeting'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ChangeRequestView } from '../../types/changeRequest'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventDateTime, formatEventTimeRange } from '../venueStaff/formatEventDateTime'

/** How many rows each panel shows before "View all". */
const TOP = 5
const read = <T,>(path: string) => () => apiClient.get<T>(path, { cache: 'no-store' })

/** A draft to finish, or a coordinator's question to answer. */
const needsAction = (r: EventRequestDto) => r.status === 'draft' || r.status === 'clarification_required'
/** An approved request whose event is still ahead: not completed or cancelled, and not yet ended. */
const isUpcoming = (r: EventRequestDto) => r.status === 'approved' && r.eventId !== null && !r.journeyStatus?.isFinal
  && (r.endDatetime === null || Date.parse(r.endDatetime) > Date.now())

/** EO15: the organiser console's front page — what needs their attention,
 * from the same reads My requests uses. */
export function OrganiserHomePage() {
  const { username } = useSession()
  const requests = useResource(read<EventRequestDto[]>('/event-requests'), 'requests')
  const changes = useResource(read<ChangeRequestView[]>('/change-requests'), 'changes')
  const greeting = greetingFor()
  const pendingChanges = changes.data ? { ...changes, data: changes.data.filter((c) => c.status === 'pending' && !c.closedReason) } : changes

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(requests, pendingChanges)}</p>}
        actions={<Link to="/organiser/requests/new" className="button button--primary">New event request</Link>}
      />
      <div className="dash-grid">
        <Panel title="Needs your action" action="View all" actionName="View all requests needing your action"
          to="/organiser/requests" className="dash-grid__wide">
          <ResourceView resource={requests} loadingText="Loading your requests…" errorText="Your requests could not be loaded."
            emptyText="Nothing needs your action right now." emptyClassName="dash-empty"
            isEmpty={(list) => !list.some(needsAction)}>
            {(list) => (
              <ul className="dash-queue" aria-label="Needs your action">
                {list.filter(needsAction).slice(0, TOP).map((request) => (
                  <li key={request.requestId}>
                    <RowLink className="dash-queue__row" title={request.eventName || 'Untitled draft'}
                      to={request.status === 'draft'
                        ? `/organiser/requests/${request.requestId}`
                        : `/organiser/requests/${request.requestId}/respond`}
                      badges={request.journeyStatus ? <JourneyStatusBadge status={request.journeyStatus} /> : null}>
                      <p>
                        {request.status === 'draft'
                          ? `Draft, edited ${formatRelativeTime(request.updatedAt)} — finish and submit it`
                          : 'The coordinator has a question — respond to it'}
                      </p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Upcoming events" action="View all" actionName="View all my events" to="/organiser/requests">
          <ResourceView resource={requests} loadingText="Loading your events…" errorText="Your events could not be loaded."
            emptyText="No upcoming events yet." emptyClassName="dash-empty" isEmpty={(list) => !list.some(isUpcoming)}>
            {(list) => (
              <ul className="dash-queue" aria-label="Upcoming events">
                {list.filter(isUpcoming)
                  .sort((a, b) => (a.startDatetime ?? '').localeCompare(b.startDatetime ?? ''))
                  .slice(0, TOP).map((request) => (
                    <li key={request.requestId}>
                      <RowLink to={`/organiser/events/${request.eventId}`} className="dash-queue__row"
                        title={request.eventName || 'Untitled event'}
                        badges={request.journeyStatus ? <JourneyStatusBadge status={request.journeyStatus} /> : null}>
                        {request.startDatetime && request.endDatetime
                          ? <p className="dash-queue__when">{formatEventTimeRange(request.startDatetime, request.endDatetime)}</p>
                          : null}
                      </RowLink>
                    </li>
                  ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Change requests waiting" action="View all" actionName="View all change requests" to="/organiser/requests">
          <ResourceView resource={pendingChanges} loadingText="Loading change requests…" errorText="Change requests could not be loaded."
            emptyText="No change requests waiting." emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Change requests waiting">
                {list.slice(0, TOP).map((change) => (
                  <li key={change.changeRequestId}>
                    <RowLink to={`/organiser/requests/${change.requestId}/changes`} className="dash-queue__row"
                      title={`Change to ${change.eventName}`}>
                      <p>Asked {formatEventDateTime(change.requestedAt)} · waiting for the coordinator</p>
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

/** "2 requests need your action · 1 change waiting", from whichever has loaded. */
function summarise(requests: Resource<EventRequestDto[]>, changes: Resource<ChangeRequestView[]>): string {
  if (requests.loading && changes.loading) return 'Checking what needs your attention…'
  const toDo = requests.data?.filter(needsAction).length
  const parts = [
    toDo !== undefined ? `${countOf(toDo, 'request')} need${toDo === 1 ? 's' : ''} your action` : null,
    changes.data ? `${countOf(changes.data.length, 'change')} waiting` : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : 'Some of what needs your attention could not be loaded.'
}
