/** Ask attendee history and event availability to read committed server state. */
export const ATTENDEE_CHANGED_EVENT = 'connectsphere:attendee-changed'
export function requestAttendeeRefresh() {
  window.dispatchEvent(new Event(ATTENDEE_CHANGED_EVENT))
}
