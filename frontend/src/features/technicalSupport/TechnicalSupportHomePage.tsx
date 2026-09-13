import { RoleConsoleHomePage } from '../../components/RoleConsoleHomePage'
import { technicalSupportFeatures } from './technicalSupportFeatures'

export function TechnicalSupportHomePage() {
  return (
    <RoleConsoleHomePage
      roleTitle="Technical Support Staff"
      basePath="/technical-support"
      features={technicalSupportFeatures}
    />
  )
}
