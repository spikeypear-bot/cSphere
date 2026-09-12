import { RoleConsoleHomePage } from '../../components/RoleConsoleHomePage'
import { venueStaffFeatures } from './venueStaffFeatures'

export function VenueStaffHomePage() {
  return <RoleConsoleHomePage roleTitle="Venue Staff" basePath="/venue-staff" features={venueStaffFeatures} />
}
