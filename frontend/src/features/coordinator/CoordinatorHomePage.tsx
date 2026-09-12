import { RoleConsoleHomePage } from '../../components/RoleConsoleHomePage'
import { coordinatorFeatures } from './coordinatorFeatures'

export function CoordinatorHomePage() {
  return (
    <RoleConsoleHomePage roleTitle="Event Coordinator" basePath="/coordinator" features={coordinatorFeatures} />
  )
}
