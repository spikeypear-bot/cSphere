import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import type { VenueBookingDto } from '../../types/venueBooking'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { EventRequirementsPanel } from './EventRequirementsPanel'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'

/** Shared by Catalogue now and VS02 later; approval actions do not belong to VS16. */
export function BookingDetailsPage() {
  const { bookingId } = useParams()
  const { data, error, retry } = useVenueRead<VenueBookingDto>(`/venue-bookings/${bookingId}`)
  return <div className="feature-skeleton booking-details-page">
    <Link to={data ? `/venue-staff/catalogue/${data.venue.venueId}` : '/venue-staff/catalogue'}>
      {data ? 'Back to venue details' : 'Back to venue catalogue'}
    </Link>
    <div><h1>Booking details</h1>{data && <p>Status: {data.status}</p>}</div>
    {error ? <div><p role="alert">{error}</p><Button onClick={retry}>Try again</Button></div>
      : !data ? <p role="status">Loading booking details…</p>
      : <div className="booking-detail-layout">
        <section aria-labelledby="venue-information-heading">
          <h2 id="venue-information-heading">Venue information</h2>
          <VenueDetailsPanel venue={data.venue} />
        </section>
        <section className="event-requirements-scroll" aria-labelledby="event-requirements-heading" tabIndex={0}>
          <EventRequirementsPanel event={data.event} />
        </section>
      </div>}
  </div>
}
