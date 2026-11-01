import type { NavItem } from '../../components/layout/navTypes'

/** The Safety Officer console's side navigation. */
export const safetyOfficerNav: NavItem[] = [
  { label: 'Dashboard', path: '/safety-officer', icon: 'dashboard' },
  { label: 'Safety reviews', path: '/safety-officer/reviews', icon: 'requests' },
]
