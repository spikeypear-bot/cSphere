import { PageHeader } from '../../components/ui/PageHeader'
import { JourneyStatusBadge } from '../../components/ui/JourneyStatus'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { countOf, greetingFor } from '../../lib/greeting'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import type { CoordinatorEventsDto } from '../../types/event'
import type { EventReassignmentRequestDto } from '../../types/eventReassignment'
import type { EventRequestDto } from '../../types/eventRequest'
import { formatEventDateTime, formatEventTimeRange } from '../venueStaff/formatEventDateTime'

/** How many rows each panel shows before "View all". */
const TOP = 5
const read = <T,>(path: string) => () => apiClient.get<T>(path, { cache: 'no-store' })

/** ECL-C1: the Lead console's front page — what needs their attention, from
 * the same reads the full pages use. */
export function CoordinatorLeadHomePage() {
  const { username } = useSession()
  const unassigned = useResource(read<EventRequestDto[]>('/event-requests/unassigned'), 'unassigned')
  const reassignments = useResource(read<EventReassignmentRequestDto[]>('/event-reassignment-requests'), 'reassignments')
  const active = useResource(read<CoordinatorEventsDto[]>('/events/by-coordinator'), 'active')
  const greeting = greetingFor()

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(unassigned, reassignments)}</p>}
      />
      <div className="dash-grid">
        <Panel title="Unassigned requests" action="View all" actionName="View all unassigned requests"
          to="/coordinator-lead/unassigned-requests" className="dash-grid__wide">
          <ResourceView resource={unassigned} loadingText="Loading unassigned requests…"
            errorText="Unassigned requests could not be loaded." emptyText="No unassigned requests right now."
            emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Unassigned requests">
                {list.slice(0, TOP).map((request) => (
                  <li key={request.requestId}>
                    <RowLink to={`/coordinator-lead/unassigned-requests/${request.requestId}`} className="dash-queue__row"
                      title={request.eventName?.trim() || 'Untitled request'}
                      badges={request.journeyStatus ? <JourneyStatusBadge status={request.journeyStatus} /> : null}>
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

        <Panel title="Reassignment requests" action="View all" actionName="View all reassignment requests"
          to="/coordinator-lead/reassignment-requests">
          <ResourceView resource={reassignments} loadingText="Loading reassignment requests…"
            errorText="Reassignment requests could not be loaded." emptyText="No reassignment requests waiting."
            emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Reassignment requests">
                {list.slice(0, TOP).map((request) => (
                  // Approve and decline happen on the full page; there is no page per request.
                  <li key={request.reassignmentRequestId}>
                    <RowLink to="/coordinator-lead/reassignment-requests" className="dash-queue__row" title={request.eventName}>
                      <p>Requested by {request.requesterName} · {formatEventDateTime(request.requestedAt)}</p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Active events" action="View all" actionName="View all active events" to="/coordinator-lead/active-events">
          <ResourceView resource={active} loadingText="Loading active events…" errorText="Active events could not be loaded."
            emptyText="No active events right now." emptyClassName="dash-empty"
            isEmpty={(holders) => holders.every((holder) => holder.events.length === 0)}>
            {(holders) => (
              <ul className="dash-queue" aria-label="Active events">
                {soonestFirst(holders).slice(0, TOP).map(({ event, coordinatorName }) => (
                  <li key={event.eventId}>
                    <RowLink to={`/coordinator-lead/events/${event.eventId}`} className="dash-queue__row" title={event.eventName}
                      badges={event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : null}>
                      <p className="dash-queue__meta">
                        <span className="dash-queue__when">{formatEventTimeRange(event.startDatetime, event.endDatetime)}</span>
                        <span>{event.coordinatorName ?? coordinatorName}</span>
                      </p>
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

/** Every coordinator's events in one list, soonest first (the server orders
 * only within each coordinator). */
function soonestFirst(holders: CoordinatorEventsDto[]) {
  return holders
    .flatMap((holder) => holder.events.map(({ event }) => ({ event, coordinatorName: holder.coordinatorName })))
    .sort((a, b) => Date.parse(a.event.startDatetime) - Date.parse(b.event.startDatetime))
}

/** "3 unassigned requests · 1 reassignment waiting", from whichever has loaded. */
function summarise(unassigned: Resource<EventRequestDto[]>, reassignments: Resource<EventReassignmentRequestDto[]>): string {
  if (unassigned.loading && reassignments.loading) return 'Checking what needs your attention…'
  const parts = [
    unassigned.data ? `${countOf(unassigned.data.length, 'unassigned request')}` : null,
    reassignments.data ? `${countOf(reassignments.data.length, 'reassignment')} waiting` : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : 'Some of what needs your attention could not be loaded.'
}
