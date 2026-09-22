import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import type { VenueBookingDto } from '../../types/venueBooking'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { EventRequirementsPanel } from './EventRequirementsPanel'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'

/** Shared by Catalogue now and VS02 later; approval actions do not belong to VS16. */
export function BookingDetailsPage() {
  const { bookingId } = useParams()
  const { data, error, retry, loading } = useVenueRead<VenueBookingDto>(`/venue-bookings/${bookingId}`, true)
  return <div className="feature-skeleton booking-details-page">
    <Link to={data ? `/venue-staff/catalogue/${data.venue.venueId}` : '/venue-staff/catalogue'}>
      {data ? 'Back to venue details' : 'Back to venue catalogue'}
    </Link>
    <div><h1>Booking details</h1>{data && <p>Status: {data.status}</p>}</div>
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
            <BookingPagination venueId={data.venue.venueId} bookingId={data.bookingId} busy={loading || !!error} />
          </div>
        </div>
      </>}
  </div>
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
