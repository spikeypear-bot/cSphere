import type { NavItem } from '../../components/layout/navTypes'

/** The Technical Support console's side navigation. */
export const technicalSupportNav: NavItem[] = [
  { label: 'Dashboard', path: '/technical-support', icon: 'dashboard' },
  { label: 'Equipment requests', path: '/technical-support/requests', icon: 'requests' },
  { label: 'Change reviews', path: '/technical-support/change-reviews', icon: 'changes' },
  { label: 'Faulty equipment', path: '/technical-support/faulty', icon: 'faulty' },
  { label: 'Inventory', path: '/technical-support/inventory', icon: 'inventory' },
]
