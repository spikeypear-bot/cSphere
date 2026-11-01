import type { NavItem } from '../../components/layout/navTypes'

/** The Event Coordinator Lead console's side navigation. */
export const coordinatorLeadNav: NavItem[] = [
  { label: 'Dashboard', path: '/coordinator-lead', icon: 'dashboard' },
  { label: 'Unassigned requests', path: '/coordinator-lead/unassigned-requests', icon: 'requests' },
  { label: 'Assignments', path: '/coordinator-lead/assignments' },
  { label: 'Reassignment requests', path: '/coordinator-lead/reassignment-requests' },
  { label: 'Active events', path: '/coordinator-lead/active-events', icon: 'calendar' },
]
