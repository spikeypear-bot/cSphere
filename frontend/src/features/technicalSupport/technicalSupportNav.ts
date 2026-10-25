import type { NavItem } from '../../components/layout/navTypes'

/** The Technical Support console's side navigation. */
export const technicalSupportNav: NavItem[] = [
  { label: 'Dashboard', path: '/technical-support' },
  { label: 'Equipment requests', path: '/technical-support/requests' },
  { label: 'Faulty equipment', path: '/technical-support/faulty' },
  { label: 'Inventory', path: '/technical-support/inventory' },
]
