import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import type { VenueDto } from '../../types/venue'
import type { VenueBookingDto } from '../../types/venueBooking'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { formatEventDateTime } from './formatEventDateTime'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'

export function VenueDetailsPage() {
  const { venueId } = useParams()
  const venue = useVenueRead<VenueDto>(`/venues/${venueId}`)
  const bookings = useVenueRead<VenueBookingDto[]>(`/venues/${venueId}/bookings`)
  return <div className="feature-skeleton venue-details-page">
    <Link to="/venue-staff/catalogue">Back to venue catalogue</Link>
    <h1>Venue details</h1>
    {venue.error ? <div><p role="alert">{venue.error}</p><Button onClick={venue.retry}>Retry venue</Button></div>
      : !venue.data ? <p role="status">Loading venue…</p> : <VenueDetailsPanel venue={venue.data} />}
    {venue.data && <section aria-labelledby="associated-bookings-heading">
      <h2 id="associated-bookings-heading">Associated bookings</h2>
      {bookings.error ? <div><p role="alert">{bookings.error}</p><Button onClick={bookings.retry}>Retry bookings</Button></div>
        : !bookings.data ? <p role="status">Loading bookings…</p>
        : bookings.data.length === 0 ? <p>No associated bookings.</p>
        : <ul className="associated-bookings">{bookings.data.map(booking => <li key={booking.bookingId}>
          <Card className="feature-skeleton__body">
            <h3>{booking.event.eventName}</h3>
            <p>{formatEventDateTime(booking.event.startDatetime)} (Singapore time)</p>
            <p>Status: {booking.status}</p>
            <Link to={`/venue-staff/bookings/${booking.bookingId}`}>View booking details</Link>
          </Card>
        </li>)}</ul>}
    </section>}
  </div>
}
