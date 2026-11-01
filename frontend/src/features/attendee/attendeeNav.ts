import type { NavItem } from '../../components/layout/navTypes'

/** The Attendee console's side navigation. */
export const attendeeNav: NavItem[] = [
  { label: 'Dashboard', path: '/attendee', icon: 'dashboard' },
  { label: 'Browse events', path: '/attendee/browse', icon: 'calendar' },
  { label: 'My registrations', path: '/attendee/my-registrations', icon: 'requests' },
]
