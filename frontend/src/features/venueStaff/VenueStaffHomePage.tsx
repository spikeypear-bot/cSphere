import { useMemo } from 'react'
import { PageHeader } from '../../components/ui/PageHeader'
import { Panel } from '../../components/ui/Panel'
import { ResourceView } from '../../components/ui/ResourceView'
import { RowLink } from '../../components/ui/RowLink'
import { apiClient } from '../../lib/apiClient'
import { countOf, greetingFor } from '../../lib/greeting'
import { useSession } from '../../lib/sessionContext'
import { useResource, type Resource } from '../../lib/useResource'
import type { VenueBookingDto } from '../../types/venueBooking'
import { BOOKING_STATUS_LABELS, bookingDisplayStatus } from '../../types/venueBookingStatus'
import { VenueMapExplorer } from '../venueMap/VenueMapExplorer'
import { formatEventTimeRange } from './formatEventDateTime'
import { useHoldDeadline } from './useHoldDeadline'
import './VenueStaff.css'

/** How many rows the panel shows before "View all". */
const TOP = 5

/** Requests still waiting on a decision: attention first, then pending ones not on a tentative hold. */
function toReview(bookings: VenueBookingDto[], expired: Set<string>) {
  // `expired` lapses holds on the server's own clock, exactly as Booking approvals does.
  const status = (b: VenueBookingDto) => bookingDisplayStatus(b.status, b.holds, expired)
  return [...bookings.filter((b) => status(b) === 'attention'), ...bookings.filter((b) => status(b) === 'pending')]
}

/** VS02: the venue staff console's front page — booking requests to decide, then the venue map. */
export function VenueStaffHomePage() {
  const { username } = useSession()
  const loaded = useResource(
    () => apiClient.get<VenueBookingDto[]>('/venue-staff/booking-requests', { cache: 'no-store' }), 'requests')
  const holds = useMemo(() => (loaded.data ?? []).flatMap((b) => b.holds ?? []), [loaded.data])
  const expired = useHoldDeadline(holds, loaded.retry)
  const requests = { ...loaded, data: loaded.data ? toReview(loaded.data, expired) : null }
  const greeting = greetingFor()

  return (
    // venue-staff-home scopes the map's field styles (VenueStaff.css).
    <div className="page venue-staff-home">
      <PageHeader
        title={username ? `${greeting}, ${username}` : greeting}
        description={<p className="page-header__description" aria-live="polite">{summarise(requests)}</p>}
      />
      <div className="dash-grid">
        <Panel title="Booking requests to review" action="View all" actionName="View all booking requests"
          to="/venue-staff/booking-approvals" className="dash-grid__wide">
          <ResourceView resource={requests} loadingText="Loading booking requests…" errorText="Booking requests could not be loaded."
            emptyText="No booking requests waiting for review." emptyClassName="dash-empty" isEmpty={(list) => list.length === 0}>
            {(list) => (
              <ul className="dash-queue" aria-label="Booking requests to review">
                {list.slice(0, TOP).map((booking) => (
                  <li key={booking.bookingId}>
                    <RowLink to={`/venue-staff/bookings/${booking.bookingId}?from=booking-approvals`} className="dash-queue__row"
                      title={booking.event.eventName?.trim() || 'Unnamed event'}
                      badges={booking.status === 'attention'
                        ? <span className="dash-badge dash-badge--danger">{BOOKING_STATUS_LABELS.attention}</span> : null}>
                      <p className="dash-queue__meta">
                        <span className="dash-queue__when">
                          {formatEventTimeRange(booking.timing?.startDatetime ?? booking.event.startDatetime,
                            booking.timing?.endDatetime ?? booking.event.endDatetime)}
                        </span>
                        <span>{booking.venue.venueAddress?.trim() || 'Venue not specified'}</span>
                      </p>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </Panel>
      </div>
      <VenueMapExplorer />
    </div>
  )
}

/** "4 booking requests to review", once loaded. */
function summarise(requests: Resource<VenueBookingDto[]>): string {
  if (requests.loading) return 'Checking what needs your attention…'
  return requests.data ? `${countOf(requests.data.length, 'booking request')} to review`
    : 'Some of what needs your attention could not be loaded.'
}
