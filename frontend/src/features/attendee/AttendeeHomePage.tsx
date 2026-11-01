import { PageHeader } from '../../components/ui/PageHeader'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import { countOf, greetingFor } from '../../lib/greeting'
import { formatEventTimeRange } from '../venueStaff/formatEventDateTime'
import { EVENT_BADGES, registrationBadge } from './attendeeStatus'
import type { AttendeeEventListItem } from './AttendeeEventsPage'
import type { RegistrationDetails } from './registrationView'

/** How many rows each panel shows before "View all". */
const TOP = 5
const read = <T,>(path: string) => () => apiClient.get<T>(path, { cache: 'no-store' })

const upcoming = (list: RegistrationDetails[]) => list.filter((r) =>
  r.registrationStatus === 'confirmed' && r.eventStatus !== 'completed' && r.eventStatus !== 'cancelled'
  && Date.parse(r.endDatetime) > Date.now())
// The same server-data mapping the Browse events badge shows.
const openNow = (list: AttendeeEventListItem[]) => list.filter((e) =>
  registrationBadge({ ...e, eventStatus: e.status }).label === 'Open for Registration')

/** AT04: the attendee console's front page — upcoming registrations and events
 * open for registration, from the same reads the full pages use. */
export function AttendeeHomePage() {
  const { username } = useSession()
  const registrations = useResource(read<RegistrationDetails[]>('/attendee/registrations'), 'registrations')
  const events = useResource(read<AttendeeEventListItem[]>('/attendee/events'), 'events')
  const greeting = greetingFor()

  return (
    <section className="page">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(registrations, events)}</p>}
      />
      <div className="dash-grid">
        <Panel title="My upcoming registrations" action="View all" actionName="View all my registrations" to="/attendee/my-registrations">
          <ResourceView resource={registrations} loadingText="Loading your registrations…" errorText="Your registrations could not be loaded."
            emptyText="You have no upcoming registrations." emptyClassName="dash-empty" isEmpty={(list) => upcoming(list).length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="My upcoming registrations">
                {upcoming(list).slice(0, TOP).map((r) => {
                  const badge = EVENT_BADGES[r.eventStatus]
                  return (
                    <li key={r.registrationId}>
                      <RowLink to={`/attendee/my-registrations/${encodeURIComponent(r.registrationId)}`} className="dash-queue__row"
                        title={r.eventName}
                        badges={r.eventStatus === 'confirmed' ? null : <span className={`dash-badge dash-badge--${badge.tone}`}>{badge.label}</span>}>
                        <p className="dash-queue__when">{formatEventTimeRange(r.startDatetime, r.endDatetime)}</p>
                      </RowLink>
                    </li>
                  )
                })}
              </ul>
            )}
          </ResourceView>
        </Panel>

        <Panel title="Open for registration" action="View all" actionName="View all events" to="/attendee/browse">
          <ResourceView resource={events} loadingText="Loading events…" errorText="Events could not be loaded."
            emptyText="No events are open for registration right now." emptyClassName="dash-empty" isEmpty={(list) => openNow(list).length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Open for registration">
                {openNow(list).slice(0, TOP).map((e) => (
                  <li key={e.eventId}>
                    <RowLink to={`/attendee/events/${encodeURIComponent(e.eventId)}`} className="dash-queue__row" title={e.eventName}
                      aside={`${countOf(e.availablePlaces ?? 0, 'place')} left`}>
                      <p className="dash-queue__when">{formatEventTimeRange(e.startDatetime, e.endDatetime)}</p>
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

/** "2 upcoming registrations · 3 events open for registration", from whichever has loaded. */
function summarise(registrations: Resource<RegistrationDetails[]>, events: Resource<AttendeeEventListItem[]>): string {
  if (registrations.loading && events.loading) return 'Checking your registrations…'
  const parts = [
    registrations.data ? countOf(upcoming(registrations.data).length, 'upcoming registration') : null,
    events.data ? `${countOf(openNow(events.data).length, 'event')} open for registration` : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : 'Your registrations and events could not be loaded.'
}
