import { Card } from '../../components/ui/Card'
import { venueAccessibilityLabels } from '../../types/venue'
import type { AccessibilityFeature } from '../../types/eventRequest'
import type { VenueBookingDto } from '../../types/venueBooking'
import { BOOKING_STATUS_LABELS, type VenueBookingDisplayStatus } from '../../types/venueBookingStatus'
import { BookingTimingConflicts } from '../../components/BookingTimingSummary'

import { formatEventDateTime } from './formatEventDateTime'

export function EventRequirementsPanel({ event, timing, status, highlightStart, highlightEnd }: {
  event: VenueBookingDto['event']
  timing?: VenueBookingDto['timing']
  status: VenueBookingDisplayStatus
  highlightStart?: boolean
  highlightEnd?: boolean
}) {
  const accessibility = event.accessibilityNeeds.map(value => value === 'none' ? 'No accessibility requirements'
    : venueAccessibilityLabels[value as AccessibilityFeature] ?? value).join(', ')
  return <Card className="feature-skeleton__body event-requirements-panel">
    <div className="event-requirements-panel__header">
      <h2 id="event-requirements-heading">Event Requirements</h2>
      <span className="venue-card-preview__tag booking-status-tag" data-booking-status={status} aria-label={'Status: ' + BOOKING_STATUS_LABELS[status]} aria-live="polite">{BOOKING_STATUS_LABELS[status]}</span>
    </div>
    <h3>{event.eventName}</h3>
    <dl className="venue-card-preview__information">
      <div><dt>Venue Occupied</dt><dd>{formatEventDateTime(timing?.effectiveStart ?? event.startDatetime)} – {formatEventDateTime(timing?.effectiveEnd ?? event.endDatetime)}</dd></div>
      <div><dt>Setup / Turnaround</dt><dd>{timing?.setupDurationMinutes ?? 0} / {timing?.turnaroundDurationMinutes ?? 0} minutes</dd></div>
      <div><dt>Booking Window</dt><dd>{highlightStart
        ? <strong className="booking-time-conflict">{formatEventDateTime(timing?.startDatetime ?? event.startDatetime)} (overlap)</strong>
        : formatEventDateTime(timing?.startDatetime ?? event.startDatetime)} – {highlightEnd
        ? <strong className="booking-time-conflict">{formatEventDateTime(timing?.endDatetime ?? event.endDatetime)} (overlap)</strong>
        : formatEventDateTime(timing?.endDatetime ?? event.endDatetime)}</dd></div>
      <div><dt>Expected Attendance</dt><dd>{event.expectedAttendance.toLocaleString()} people</dd></div>
      <div><dt>Venue Requirements</dt><dd>{event.venueRequirements?.trim() || 'Not specified'}</dd></div>
      <div><dt>Accessibility Requirements</dt><dd>{accessibility || 'Not specified'}</dd></div>
      <div><dt>Equipment Requirements</dt><dd>{event.equipmentRequirements?.trim() || 'Not specified'}</dd></div>
    </dl>
    <BookingTimingConflicts timing={timing} bookingLinks />
  </Card>
}
