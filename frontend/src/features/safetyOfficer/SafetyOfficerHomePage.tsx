import { PageHeader } from '../../components/ui/PageHeader'
import { JourneyStatusBadge } from '../../components/ui/JourneyStatus'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import { countOf, greetingFor } from '../../lib/greeting'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import type { SafetyEventListItem } from './readinessTypes'

/** How many rows each panel shows before "View all". */
const TOP = 5
const read = (path: string) => () => apiClient.get<SafetyEventListItem[]>(path, { cache: 'no-store' })
// EC08: only events actually sent are the Safety Officer's queue (as on Safety reviews).
const isAwaiting = (event: SafetyEventListItem) => event.status === 'awaiting_safety_review'

/** SO-C1: the Safety Officer console's front page — the top of each Safety
 * reviews section, from the same reads that page uses. */
export function SafetyOfficerHomePage() {
  const { username } = useSession()
  const planning = useResource(read('/safety/events/planning'), 'planning')
  const confirmed = useResource(read('/safety/events/confirmed'), 'confirmed')
  const greeting = greetingFor()

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(planning)}</p>}
      />
      <div className="dash-grid">
        <EventPanel title="Awaiting your review" actionName="View all events awaiting your review" className="dash-grid__wide"
          resource={planning} pick={(list) => list.filter(isAwaiting)}
          errorText="Events awaiting your review could not be loaded." emptyText="No events are waiting for your safety review." />
        <EventPanel title="In planning" actionName="View all events in planning"
          resource={planning} pick={(list) => list.filter((event) => !isAwaiting(event))}
          errorText="Events in planning could not be loaded." emptyText="No other events are being planned." />
        <EventPanel title="Confirmed" actionName="View all confirmed events"
          resource={confirmed} pick={(list) => list}
          errorText="Confirmed events could not be loaded." emptyText="No confirmed events to show." />
      </div>
    </section>
  )
}

function EventPanel({ title, actionName, className, resource, pick, errorText, emptyText }: {
  title: string
  actionName: string
  className?: string
  resource: Resource<SafetyEventListItem[]>
  pick: (list: SafetyEventListItem[]) => SafetyEventListItem[]
  errorText: string
  emptyText: string
}) {
  return (
    <Panel title={title} action="View all" actionName={actionName} to="/safety-officer/reviews" className={className}>
      <ResourceView resource={resource} loadingText="Loading events…" errorText={errorText}
        emptyText={emptyText} emptyClassName="dash-empty" isEmpty={(list) => pick(list).length === 0}>
        {(list) => (
          <ul className="dash-queue" aria-label={title}>
            {pick(list).slice(0, TOP).map((event) => (
              <li key={event.eventId}>
                <RowLink to={`/safety-officer/events/${event.eventId}`} className="dash-queue__row" title={event.eventName}
                  badges={event.journeyStatus ? <JourneyStatusBadge status={event.journeyStatus} /> : null}>
                  <p className="dash-queue__meta">
                    <span className="dash-queue__when">{formatEventTimeRange(event.startDatetime, event.endDatetime)}</span>
                    <span>{event.venueAddress || 'Venue not finalised'}</span>
                  </p>
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </Panel>
  )
}

/** "2 events awaiting your review", once the planning list has loaded. */
function summarise(planning: Resource<SafetyEventListItem[]>): string {
  if (planning.loading) return 'Checking what needs your attention…'
  if (!planning.data) return 'Events awaiting your review could not be loaded.'
  return `${countOf(planning.data.filter(isAwaiting).length, 'event')} awaiting your review`
}
