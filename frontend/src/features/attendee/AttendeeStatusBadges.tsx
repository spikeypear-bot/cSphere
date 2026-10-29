import { EVENT_BADGES, registrationBadge, type AttendeeStatusInput } from './attendeeStatus'
import { OutlinedStatusBadge } from '../../components/ui/OutlinedStatusBadge'

/** Exactly two badges, always lifecycle first and personal registration/availability second. */
export function AttendeeStatusBadges({ value }: { value: AttendeeStatusInput }) {
  const event = EVENT_BADGES[value.eventStatus]
  const registration = registrationBadge(value)
  return <dl className="attendee-badges" aria-label="Event and registration statuses">
    <div><dt>Event status</dt><dd><span className="attendee-status" data-attendee-badge="event" data-tone={event.tone}>{event.label}</span></dd></div>
    <div><dt>Registration status</dt><dd><OutlinedStatusBadge data-attendee-badge="registration" tone={registration.tone}>{registration.label}</OutlinedStatusBadge></dd></div>
  </dl>
}
