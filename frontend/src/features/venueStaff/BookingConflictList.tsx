import { BOOKING_STATUS_LABELS, type VenueBookingStatus } from '../../types/venueBookingStatus'
import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { formatEventTimeRange as range } from './formatEventDateTime'
import type { ScheduleBooking } from './VenueUnavailabilityPanel'
import './BookingConflictList.css'

export interface BookingOverlap { first: ScheduleBooking; second: ScheduleBooking }

/** Shared presentation for the current schedule and the venue's active-conflict GET. */
export function BookingConflictList({ pairs, venueName, showHeading = true }: { pairs: BookingOverlap[]; venueName?: string; showHeading?: boolean }) {
  if (!pairs.length) return null
  return <section className="booking-conflicts" aria-label="Booking Conflicts">
    {showHeading && <h2>Booking Conflicts</h2>}
    {showHeading && <p>Reserved periods overlap. Review the affected bookings with their coordinators.</p>}
    {<ul className="booking-conflict-list">
      {pairs.map(({ first, second }) => {
        const start = Date.parse(first.effectiveStart) > Date.parse(second.effectiveStart) ? first.effectiveStart : second.effectiveStart
        const end = Date.parse(first.effectiveEnd) < Date.parse(second.effectiveEnd) ? first.effectiveEnd : second.effectiveEnd
        return <li key={[first.bookingId, second.bookingId].sort().join(':')}><Card className="booking-conflict-list__card">
          {[first, second].map(b => <div key={b.bookingId}>
            <h3><Link to={`/venue-staff/bookings/${b.bookingId}`}>{b.eventName}</Link></h3>
            <dl className="booking-conflict-list__information">
              <div><dt>Venue</dt><dd>{b.venueName ?? venueName ?? b.venueId}</dd></div>
              <div><dt>Status</dt><dd><span className="venue-card-preview__tag booking-status-tag" data-booking-status={b.status}>{BOOKING_STATUS_LABELS[b.status as VenueBookingStatus] ?? 'Unknown status'}</span></dd></div>
              <div><dt>Advertised event time (SGT)</dt><dd>{range(b.eventStartDateTime ?? b.startDateTime, b.eventEndDateTime ?? b.endDateTime)}</dd></div>
              <div><dt>Booking window (SGT)</dt><dd>{range(b.startDateTime, b.endDateTime)}</dd></div>
              <div><dt>Setup / turnaround</dt><dd>{b.setupDurationMinutes ?? (Date.parse(b.startDateTime) - Date.parse(b.effectiveStart)) / 60000} / {b.turnaroundDurationMinutes ?? (Date.parse(b.effectiveEnd) - Date.parse(b.endDateTime)) / 60000} minutes</dd></div>
              <div><dt>Effective occupied period (SGT)</dt><dd>{range(b.effectiveStart, b.effectiveEnd)}</dd></div>
            </dl>
          </div>)}
          <p><strong>Conflict period (SGT):</strong> {range(start, end)}</p>
        </Card></li>
      })}
    </ul>}
  </section>
}
