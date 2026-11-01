import type { NavItem } from '../../components/layout/navTypes'

/** The Event Organiser console's side navigation. */
export const organiserNav: NavItem[] = [
  { label: 'Dashboard', path: '/organiser', icon: 'dashboard' },
  { label: 'My requests', path: '/organiser/requests', icon: 'requests' },
  { label: 'New request', path: '/organiser/requests/new' },
]
