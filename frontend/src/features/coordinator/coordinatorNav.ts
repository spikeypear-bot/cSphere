import type { NavItem } from '../../components/layout/navTypes'

/** The Event Coordinator console's side navigation. */
export const coordinatorNav: NavItem[] = [
  { label: 'Dashboard', path: '/coordinator', icon: 'dashboard' },
  { label: 'Review queue', path: '/coordinator/review-queue', icon: 'requests' },
  { label: 'My events', path: '/coordinator/my-events', icon: 'calendar' },
  { label: 'Venue issues', path: '/coordinator/operational-issues', icon: 'faulty' },
  { label: 'Search venues', path: '/coordinator/venue-search', icon: 'search' },
]
