import { Navigate, Route, Routes } from 'react-router-dom'
import { SessionProvider } from './lib/session'
import { useSession, type Role } from './lib/sessionContext'
import { AppShell } from './components/AppShell'
import { FeatureSkeletonPage } from './components/FeatureSkeletonPage'
import type { SkeletonFeature } from './types/skeletonFeature'
import { RoleSelectPage } from './features/roleSelect/RoleSelectPage'
import { OrganiserHomePage } from './features/organiser/OrganiserHomePage'
import { EventRequestWizardPage } from './features/organiser/EventRequestWizardPage'
import { organiserExtraFeatures } from './features/organiser/organiserExtraFeatures'
import { CoordinatorHomePage } from './features/coordinator/CoordinatorHomePage'
import { coordinatorFeatures } from './features/coordinator/coordinatorFeatures'
import { VenueStaffHomePage } from './features/venueStaff/VenueStaffHomePage'
import { VenueCreatePage } from './features/venueStaff/VenueCreatePage'
import { VenueCataloguePage } from './features/venueStaff/VenueCataloguePage'
import { venueStaffFeatures } from './features/venueStaff/venueStaffFeatures'
import { TechnicalSupportHomePage } from './features/technicalSupport/TechnicalSupportHomePage'
import { technicalSupportFeatures } from './features/technicalSupport/technicalSupportFeatures'
import { AttendeeHomePage } from './features/attendee/AttendeeHomePage'
import { attendeeFeatures } from './features/attendee/attendeeFeatures'

/** Route-level role gating (front-end only, for this interim phase — see
 * docs/decision-log.md Q2 for whether server-side enforcement is also
 * needed): any role other than `expected` is sent back to the selector
 * rather than shown that role's pages. */
function useRoleGate(expected: Role) {
  const { role } = useSession()
  return role === expected
}

function skeletonRoutes(features: SkeletonFeature[]) {
  return features.map((feature) => (
    <Route key={feature.path} path={feature.path} element={<FeatureSkeletonPage feature={feature} />} />
  ))
}

function OrganiserRoutes() {
  if (!useRoleGate('organiser')) return <Navigate to="/" replace />
  return (
    <Routes>
      <Route index element={<OrganiserHomePage />} />
      <Route path="requests/new" element={<EventRequestWizardPage />} />
      <Route path="requests/:requestId" element={<EventRequestWizardPage />} />
      {skeletonRoutes(organiserExtraFeatures)}
    </Routes>
  )
}

function CoordinatorRoutes() {
  if (!useRoleGate('coordinator')) return <Navigate to="/" replace />
  return (
    <Routes>
      <Route index element={<CoordinatorHomePage />} />
      {skeletonRoutes(coordinatorFeatures)}
    </Routes>
  )
}

function VenueStaffRoutes() {
  if (!useRoleGate('venue-staff')) return <Navigate to="/" replace />
  return (
    <Routes>
      <Route index element={<VenueStaffHomePage />} />
      <Route path="catalogue" element={<VenueCataloguePage />} />
      <Route path="catalogue/new" element={<VenueCreatePage />} />
      {skeletonRoutes(venueStaffFeatures.filter(feature => !feature.path.startsWith('catalogue')))}
    </Routes>
  )
}

function TechnicalSupportRoutes() {
  if (!useRoleGate('technical-support')) return <Navigate to="/" replace />
  return (
    <Routes>
      <Route index element={<TechnicalSupportHomePage />} />
      {skeletonRoutes(technicalSupportFeatures)}
    </Routes>
  )
}

function AttendeeRoutes() {
  if (!useRoleGate('attendee')) return <Navigate to="/" replace />
  return (
    <Routes>
      <Route index element={<AttendeeHomePage />} />
      {skeletonRoutes(attendeeFeatures)}
    </Routes>
  )
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<RoleSelectPage />} />
      <Route path="/organiser/*" element={<OrganiserRoutes />} />
      <Route path="/coordinator/*" element={<CoordinatorRoutes />} />
      <Route path="/venue-staff/*" element={<VenueStaffRoutes />} />
      <Route path="/technical-support/*" element={<TechnicalSupportRoutes />} />
      <Route path="/attendee/*" element={<AttendeeRoutes />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <SessionProvider>
      <AppShell>
        <AppRoutes />
      </AppShell>
    </SessionProvider>
  )
}
