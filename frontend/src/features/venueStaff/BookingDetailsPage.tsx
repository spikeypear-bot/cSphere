import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import type { VenueBookingDto } from '../../types/venueBooking'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { EventRequirementsPanel } from './EventRequirementsPanel'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'

/** Shared read-only details for Catalogue and the VS02 pending queue. */
export function BookingDetailsPage() {
  const { bookingId } = useParams()
  const [searchParams] = useSearchParams()
  const fromApprovals = searchParams.get('from') === 'booking-approvals'
  const { data, error, retry, loading } = useVenueRead<VenueBookingDto>(`/venue-bookings/${bookingId}`, !fromApprovals)
  return <div className="feature-skeleton booking-details-page">
    <Link to={fromApprovals ? '/venue-staff/booking-approvals' : data ? `/venue-staff/catalogue/${data.venue.venueId}` : '/venue-staff/catalogue'}>
      {fromApprovals ? 'Back to Pending Booking Requests' : data ? 'Back to venue details' : 'Back to venue catalogue'}
    </Link>
    <div><h1>Booking details</h1>{data && <p>Status: {data.status}</p>}</div>
    {fromApprovals && data && !loading && !error && data.status !== 'pending' && <p role="status">
      This booking request is no longer pending. Its current status is {data.status}.
    </p>}
    {error && <div><p role="alert">{error}{data && " Showing the previously loaded booking; the requested booking could not be loaded."}</p><Button onClick={retry}>Try again</Button></div>}
    {!data ? (error ? null : <p role="status">Loading booking details…</p>)
      : <>
        <h2 id="venue-information-heading">Venue information</h2>
        <div className="booking-detail-layout">
          <section aria-labelledby="venue-information-heading">
            <VenueDetailsPanel venue={data.venue} />
          </section>
          <div className="booking-requirements-column" aria-busy={loading}>
            {loading && <span className="booking-loading-notice" role="status">Loading next booking…</span>}
            <section aria-labelledby="event-requirements-heading">
              <EventRequirementsPanel event={data.event} />
            </section>
            {(data.bookingNotes || data.suitabilityNote) && <CoordinatorNotes booking={data} />}
            {!fromApprovals && <BookingPagination venueId={data.venue.venueId} bookingId={data.bookingId} busy={loading || !!error} />}
          </div>
        </div>
      </>}
  </div>
}

/** EC03: what the Event Coordinator wrote when requesting this venue. */
function CoordinatorNotes({ booking }: { booking: VenueBookingDto }) {
  return <section aria-labelledby="coordinator-notes-heading" className="booking-coordinator-notes">
    <h2 id="coordinator-notes-heading">From the Event Coordinator</h2>
    {booking.suitabilityNote && <p className="booking-coordinator-notes__warning">
      <strong>Does not meet every accessibility requirement.</strong> Coordinator's justification: {booking.suitabilityNote}
    </p>}
    {booking.bookingNotes && <p>{booking.bookingNotes}</p>}
  </section>
}

function BookingPagination({ venueId, bookingId, busy }: { venueId: string; bookingId: string; busy: boolean }) {
  const navigate = useNavigate()
  const { data, error, retry } = useVenueRead<VenueBookingDto[]>(`/venues/${venueId}/bookings`)
  if (error) return <div><p role="alert">Could not load booking navigation: {error}</p><Button onClick={retry}>Retry booking navigation</Button></div>
  if (!data) return <p role="status">Loading booking navigation…</p>
  const index = data.findIndex(booking => booking.bookingId === bookingId)
  if (index < 0) return <p>This booking is no longer in the venue’s booking list.</p>
  const previousDisabled = busy || index === 0
  const nextDisabled = busy || index === data.length - 1
  return <nav className="booking-pagination" aria-label="Venue bookings">
    <Button type="button" variant="secondary" aria-disabled={previousDisabled}
      onClick={() => { if (!previousDisabled) void navigate(`/venue-staff/bookings/${data[index - 1].bookingId}`) }}>Previous</Button>
    <span aria-live="polite">Booking {index + 1} of {data.length}</span>
    <Button type="button" variant="secondary" aria-disabled={nextDisabled}
      onClick={() => { if (!nextDisabled) void navigate(`/venue-staff/bookings/${data[index + 1].bookingId}`) }}>Next</Button>
  </nav>
}
