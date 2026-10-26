import { BOOKING_STATUS_LABELS, bookingDisplayStatus } from '../../types/venueBookingStatus'
import { NO_HOLDS, useHoldDeadline } from './useHoldDeadline'
import { Link, useParams } from 'react-router-dom'
import { useMemo, useState } from 'react'
import { VenueUnavailabilityPanel, VenueOccupancySettings } from './VenueUnavailabilityPanel'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/PageHeader'
import { RefreshIcon } from '../../components/ui/RefreshIcon'
import { Card } from '../../components/ui/Card'
import type { VenueDto } from '../../types/venue'
import type { VenueBookingDto } from '../../types/venueBooking'
import { VenueDetailsPanel } from './VenueDetailsPanel'
import { VenueOperationalIssuesPanel } from './VenueOperationalIssuesPanel'
import { formatEventDateTime, formatEventTimeRange } from './formatEventDateTime'
import { useVenueRead } from './useVenueRead'
import './BookingDetailsPage.css'
import { TimingConflictReview } from './TimingConflictReview'

export function VenueDetailsPage() {
  const { venueId } = useParams()
  const [bookingRevision, setBookingRevision] = useState(0)
  const venue = useVenueRead<VenueDto>(`/venues/${venueId}`, false, 5000, true)
  return <div className="feature-skeleton venue-details-page">
    <Link to="/venue-staff/catalogue">Back to venue catalogue</Link>
    <PageHeader
      title="Venue details"
      actions={
        <Button className="venue-refresh-button" variant="ghost" onClick={venue.retry}
          disabled={venue.loading} aria-label="Refresh venue" title="Refresh venue">
          <RefreshIcon />
          <span>Refresh</span>
        </Button>
      }
    />
    {venue.error ? <div><p role="alert">{venue.error}</p><Button onClick={venue.retry}>Retry venue</Button></div>
      : !venue.data ? <p role="status">Loading venue…</p> : <VenueDetailsPanel venue={venue.data} />}
    {venue.data && <VenueOccupancySettings key={venue.data.venueId} venueId={venue.data.venueId} onSaved={venue.retry} />}
    {venue.data && <TimingConflictReview key={`conflicts:${venue.data.venueId}`} venueId={venue.data.venueId} />}
    {venue.data && <AssociatedBookings key={`bookings-${venue.data.venueId}-${bookingRevision}`} venueId={venue.data.venueId} />}
    {venue.data && <VenueUnavailabilityPanel key={`unavailability-${venue.data.venueId}`} venueId={venue.data.venueId}
      onRecorded={() => setBookingRevision(n => n + 1)} />}
    {venue.data && <VenueOperationalIssuesPanel key={`issues-${venue.data.venueId}`}
      venueId={venue.data.venueId} venueAddress={venue.data.venueAddress} />}
  </div>
}

function AssociatedBookings({ venueId }: { venueId: string }) {
  const bookings = useVenueRead<VenueBookingDto[]>(`/venues/${venueId}/bookings`, false, 5000, true)
  const holds = useMemo(() => bookings.data?.flatMap(b => b.holds ?? []) ?? NO_HOLDS, [bookings.data])
  const expired = useHoldDeadline(holds, bookings.retry)
  return <section aria-labelledby="associated-bookings-heading">
      <h2 id="associated-bookings-heading">Associated bookings</h2>
      {bookings.error ? <div><p role="alert">{bookings.error}</p><Button onClick={bookings.retry}>Retry bookings</Button></div>
        : !bookings.data ? <p role="status">Loading bookings…</p>
        : bookings.data.length === 0 ? <p>No associated bookings.</p>
        : <ul className="associated-bookings">{bookings.data.map(booking => <li key={booking.bookingId}>
          <Card className="feature-skeleton__body">
            <h3>{booking.event.eventName}</h3>
            <p>Event ID: {booking.event.eventId} · Booking ID: {booking.bookingId}</p>
            <p>{formatEventDateTime(booking.timing?.startDatetime ?? booking.event.startDatetime)} (Singapore time)</p>
            {booking.timing && <p><strong>Venue Occupied (SGT):</strong> {formatEventTimeRange(booking.timing.effectiveStart, booking.timing.effectiveEnd)}</p>}
            <p>Status: <span className="venue-card-preview__tag booking-status-tag" data-booking-status={bookingDisplayStatus(booking.status ?? 'pending', booking.holds, expired)}>{BOOKING_STATUS_LABELS[bookingDisplayStatus(booking.status ?? 'pending', booking.holds, expired)]}</span></p>
            {booking.requiresAlternative && <p className="field-error">Alternative arrangements required</p>}
            <Link className="button button--secondary" to={`/venue-staff/bookings/${booking.bookingId}`}>View booking details</Link>
          </Card>
        </li>)}</ul>}
    </section>
}
