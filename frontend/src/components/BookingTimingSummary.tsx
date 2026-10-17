import type { BookingTiming } from '../types/bookingTiming'
import { Link } from 'react-router-dom'
import { formatEventTimeRange as range } from '../features/venueStaff/formatEventDateTime'

export function BookingTimingSummary({ timing, headingLevel = 3, bookingLinks = false }: { timing?: BookingTiming | null; headingLevel?: 2 | 3; bookingLinks?: boolean }) {
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  if (!timing) return null
  return <section aria-label="Booking timings">
    <Heading>Booking Timings (SGT)</Heading>
    <dl className="venue-card-preview__information">
      <div><dt>Booking Window</dt><dd>{range(timing.startDatetime, timing.endDatetime)}</dd></div>
      <div><dt>Setup / Turnaround</dt><dd>{timing.setupDurationMinutes} / {timing.turnaroundDurationMinutes} minutes</dd></div>
      <div><dt>Venue Occupied</dt><dd>{range(timing.effectiveStart, timing.effectiveEnd)}</dd></div>
      {timing.timingNote && <div><dt>Timing Reason</dt><dd>{timing.timingNote}</dd></div>}
    </dl>
    <BookingTimingConflicts timing={timing} />
  </section>
}

export function BookingTimingConflicts({ timing }: { timing?: BookingTiming | null }) {
  return !!timing?.conflicts?.length && <div role="status" className="unavailability-warning">
      <p>Occupied time overlaps approved bookings or tentative holds. Review these arrangements with Venue Staff; no booking has been cancelled.</p>
      <ul>{timing.conflicts.map(conflict => <li key={conflict.bookingId}>
        {bookingLinks ? <Link to={`/venue-staff/bookings/${conflict.bookingId}`}>{conflict.eventName}</Link> : conflict.eventName}: {range(conflict.effectiveStart ?? conflict.startDatetime, conflict.effectiveEnd ?? conflict.endDatetime)}
      </li>)}</ul>
    </div>
}
