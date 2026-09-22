import { Card } from '../../components/ui/Card'
import { venueAccessibilityLabels } from '../../types/venue'
import type { AccessibilityFeature } from '../../types/eventRequest'
import type { VenueBookingDto } from '../../types/venueBooking'

import { formatEventDateTime } from './formatEventDateTime'

export function EventRequirementsPanel({ event }: { event: VenueBookingDto['event'] }) {
  const accessibility = event.accessibilityNeeds.map(value => value === 'none' ? 'No accessibility requirements'
    : venueAccessibilityLabels[value as AccessibilityFeature] ?? value).join(', ')
  return <Card className="feature-skeleton__body event-requirements-panel">
    <h2 id="event-requirements-heading">Event requirements</h2>
    <h3>{event.eventName}</h3>
    <dl className="venue-card-preview__information">
      <div><dt>Starts (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(event.startDatetime)}</dd></div>
      <div><dt>Ends (Singapore time, UTC+08:00)</dt><dd>{formatEventDateTime(event.endDatetime)}</dd></div>
      <div><dt>Expected attendance</dt><dd>{event.expectedAttendance.toLocaleString()} people</dd></div>
      <div><dt>Venue requirements</dt><dd>{event.venueRequirements?.trim() || 'Not specified'}</dd></div>
      <div><dt>Accessibility requirements</dt><dd>{accessibility || 'Not specified'}</dd></div>
      <div><dt>Equipment requirements</dt><dd>{event.equipmentRequirements?.trim() || 'Not specified'}</dd></div>
    </dl>
  </Card>
}
