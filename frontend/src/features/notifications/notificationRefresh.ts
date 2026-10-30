/** Request a fresh server read after a successful action; never invent a local notification. */
export const NOTIFICATIONS_CHANGED_EVENT = 'connectsphere:notifications-changed'

export function requestNotificationRefresh() {
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED_EVENT))
}
