import { Card } from '../../components/ui/Card'
import type { VenueBookingConflict, VenueBookingDto, VenueUnavailabilityConflict } from '../../types/venueBooking'
import { bookingConflictHighlights } from './bookingConflictHighlights'
import { formatEventDateTime } from './formatEventDateTime'

export function BookingConflictCard({ conflict, request }: {
  conflict: VenueBookingConflict
  request: VenueBookingDto['event']
}) {
  const highlights = bookingConflictHighlights(request, conflict)
  return <Card className="booking-conflict-card" role="group" aria-label={`Conflicting booking: ${conflict.eventName}`}>
    <dl className="booking-conflict-card__fields">
      <div className="booking-conflict-card__name"><dt>Booking Name</dt><dd>{conflict.eventName}</dd>
        <dd className="booking-conflict-card__id">Booking ID: {conflict.bookingId}</dd></div>
      <div><dt>Starts</dt><dd><ConflictDateTime value={conflict.startDatetime} highlighted={highlights.existingStart} /></dd></div>
      <div><dt>Ends</dt><dd><ConflictDateTime value={conflict.endDatetime} highlighted={highlights.existingEnd} /></dd></div>
      <div><dt>Setup Period</dt><dd>Not available yet</dd></div>
      <div><dt>Turnaround Period</dt><dd>Not available yet</dd></div>
    </dl>
  </Card>
}

function ConflictDateTime({ value, highlighted }: { value: string; highlighted: boolean }) {
  const text = formatEventDateTime(value)
  return highlighted ? <strong className="booking-time-conflict">{text}<span className="booking-conflict-card__hint"> (overlap)</span></strong> : <>{text}</>
}

export function UnavailabilityConflictCard({ period, request }: {
  period: VenueUnavailabilityConflict
  request: VenueBookingDto['event']
}) {
  const highlights = bookingConflictHighlights(request, period)
  return <Card className="booking-conflict-card" role="group" aria-label={`Unavailable period: ${period.reason}`}>
    <dl className="booking-conflict-card__fields">
      <div className="booking-conflict-card__name"><dt>Unavailability Reason</dt><dd>{period.reason}</dd></div>
      <div><dt>Starts</dt><dd><ConflictDateTime value={period.startDatetime} highlighted={highlights.existingStart} /></dd></div>
      <div><dt>Ends</dt><dd><ConflictDateTime value={period.endDatetime} highlighted={highlights.existingEnd} /></dd></div>
    </dl>
  </Card>
}
