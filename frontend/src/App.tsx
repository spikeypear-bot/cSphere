import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from './lib/session'
import { HOME_BY_ROLE, useSession, type Role } from './lib/sessionContext'
import { AppShell } from './components/AppShell'
import { FeatureSkeletonPage } from './components/FeatureSkeletonPage'
import type { SkeletonFeature } from './types/skeletonFeature'
import { LoginPage } from './features/auth/LoginPage'
import { OrganiserHomePage } from './features/organiser/OrganiserHomePage'
import { EventRequestWizardPage } from './features/organiser/EventRequestWizardPage'
import { organiserExtraFeatures } from './features/organiser/organiserExtraFeatures'
import { CoordinatorHomePage } from './features/coordinator/CoordinatorHomePage'
import { coordinatorFeatures } from './features/coordinator/coordinatorFeatures'
import { VenueStaffHomePage } from './features/venueStaff/VenueStaffHomePage'
import { VenueEditPage } from './features/venueStaff/VenueEditPage'
import { VenueCreatePage } from './features/venueStaff/VenueCreatePage'
import { VenueCataloguePage } from './features/venueStaff/VenueCataloguePage'
import { venueStaffFeatures } from './features/venueStaff/venueStaffFeatures'
import { TechnicalSupportHomePage } from './features/technicalSupport/TechnicalSupportHomePage'
import { technicalSupportFeatures } from './features/technicalSupport/technicalSupportFeatures'
import { EquipmentStatusPage } from './features/technicalSupport/status/equipmentStatusPage'
import { AttendeeHomePage } from './features/attendee/AttendeeHomePage'
import { attendeeFeatures } from './features/attendee/attendeeFeatures'

/** Route-level role gating. The role comes from the access token's `role`
 * claim (D19), so editing localStorage no longer promotes anyone: a tampered
 * token fails signature verification and every API call 401s. This gate is
 * therefore about not showing someone a console full of requests that would
 * all fail — the enforcement itself is D20's rules on the server. */
function useRoleGate(expected: Role) {
  const { role } = useSession()
  return role === expected
}

/** Where to send someone who asked for a console that isn't theirs. Signed in:
 * their own console, since that is the only one they can use. Signed out: the
 * login page, carrying the path so it can say what they were trying to open. */
function redirectFor(role: Role | null, pathname: string): string {
  return role ? HOME_BY_ROLE[role] : `/?access-denied=${encodeURIComponent(pathname)}`
}

function skeletonRoutes(features: SkeletonFeature[]) {
  return features.map((feature) => (
    <Route key={feature.path} path={feature.path} element={<FeatureSkeletonPage feature={feature} />} />
  ))
}

function OrganiserRoutes() {
  const permitted = useRoleGate('organiser')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
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
  const permitted = useRoleGate('coordinator')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <Routes>
      <Route index element={<CoordinatorHomePage />} />
      {skeletonRoutes(coordinatorFeatures)}
    </Routes>
  )
}

function VenueStaffRoutes() {
  const permitted = useRoleGate('venue-staff')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <Routes>
      <Route index element={<VenueStaffHomePage />} />
      <Route path="catalogue" element={<VenueCataloguePage />} />
      <Route path="catalogue/new" element={<VenueCreatePage />} />
      <Route path="catalogue/:venueId/edit" element={<VenueEditPage />} />
      {skeletonRoutes(venueStaffFeatures.filter(feature => !feature.path.startsWith('catalogue')))}
    </Routes>
  )
}

function TechnicalSupportRoutes() {
  const permitted = useRoleGate('technical-support')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <Routes>
      <Route index element={<TechnicalSupportHomePage />} />
      {skeletonRoutes(
        technicalSupportFeatures.filter((feature) => feature.path !== 'status'),
      )}
      <Route path="status" element={<EquipmentStatusPage />} />
    </Routes>
  )
}

function AttendeeRoutes() {
  const permitted = useRoleGate('attendee')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <Routes>
      <Route index element={<AttendeeHomePage />} />
      {skeletonRoutes(attendeeFeatures)}
    </Routes>
  )
}

/** "/" is the login page when signed out, and a redirect to your own console
 * when signed in — there is no role to pick any more. */
function LandingRoute() {
  const { role } = useSession()
  return role ? <Navigate to={HOME_BY_ROLE[role]} replace /> : <LoginPage />
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LandingRoute />} />
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
