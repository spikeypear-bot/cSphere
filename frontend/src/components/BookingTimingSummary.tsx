import type { BookingTiming } from '../types/bookingTiming'
import { formatEventTimeRange as range } from '../features/venueStaff/formatEventDateTime'

export function BookingTimingSummary({ timing, headingLevel = 3 }: { timing?: BookingTiming | null; headingLevel?: 2 | 3 }) {
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
    {(timing.conflicts ?? []).length > 0 && <div role="status" className="unavailability-warning">
      <p>Occupied time overlaps approved bookings. Review these arrangements with Venue Staff; no booking has been cancelled.</p>
      <ul>{timing.conflicts.map(conflict => <li key={conflict.bookingId}>
        {conflict.eventName}: {range(conflict.effectiveStart ?? conflict.startDatetime, conflict.effectiveEnd ?? conflict.endDatetime)}
      </li>)}</ul>
    </div>}
  </section>
}
