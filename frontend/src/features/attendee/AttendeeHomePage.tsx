import { RoleConsoleHomePage } from '../../components/RoleConsoleHomePage'
import { attendeeFeatures } from './attendeeFeatures'

export function AttendeeHomePage() {
  return <RoleConsoleHomePage roleTitle="Attendee" basePath="/attendee" features={attendeeFeatures} />
}
