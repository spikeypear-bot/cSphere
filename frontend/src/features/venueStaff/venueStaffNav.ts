import type { NavItem } from '../../components/layout/navTypes'

/** The Venue Staff console's side navigation. */
export const venueStaffNav: NavItem[] = [
  { label: 'Dashboard', path: '/venue-staff', icon: 'dashboard' },
  { label: 'Booking approvals', path: '/venue-staff/booking-approvals', icon: 'requests' },
  { label: 'Venue catalogue', path: '/venue-staff/catalogue', icon: 'inventory' },
  { label: 'Availability calendar', path: '/venue-staff/availability-calendar', icon: 'calendar' },
]
