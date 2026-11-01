import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from './lib/session'
import { HOME_BY_ROLE, useSession, type Role } from './lib/sessionContext'
import { AppShell } from './components/AppShell'
import { LoginPage } from './features/auth/LoginPage'
import { OrganiserHomePage } from './features/organiser/OrganiserHomePage'
import { MyRequestsPage } from './features/organiser/MyRequestsPage'
import { organiserNav } from './features/organiser/organiserNav'
import { EventRequestWizardPage } from './features/organiser/EventRequestWizardPage'
import { CoordinatorHomePage } from './features/coordinator/CoordinatorHomePage'
import { ReviewQueuePage } from './features/coordinator/ReviewQueuePage'
import { AssignedEventsPage } from './features/coordinator/AssignedEventsPage'
import { EventReassignmentRequestPage } from './features/coordinator/EventReassignmentRequestPage'
import { RequestReviewPage } from './features/coordinator/RequestReviewPage'
import { VenueBookingRequestPage } from './features/coordinator/VenueBookingRequestPage'
import { VenueSearchPage } from './features/coordinator/VenueSearchPage'
import { OperationalIssuesPage } from './features/coordinator/OperationalIssuesPage'
import { EquipmentRequestPage } from './features/coordinator/EquipmentRequestPage'
import { ActiveEventsPage } from './features/coordinatorLead/ActiveEventsPage'
import { CoordinatorAssignmentsPage } from './features/coordinatorLead/CoordinatorAssignmentsPage'
import { EventReassignmentRequestsPage } from './features/coordinatorLead/EventReassignmentRequestsPage'
import { ChangeRequestFormPage } from './features/changeRequests/ChangeRequestFormPage'
import { ChangeRequestRedirect, ChangeRequestsPage } from './features/changeRequests/ChangeRequestsPage'
import { ChangeRequestReviewPage } from './features/changeRequests/ChangeRequestReviewPage'
import { CoordinatorLeadHomePage } from './features/coordinatorLead/CoordinatorLeadHomePage'
import { coordinatorLeadNav } from './features/coordinatorLead/coordinatorLeadNav'
import { IncomingRequestReviewPage } from './features/coordinatorLead/IncomingRequestReviewPage'
import { UnassignedRequestsPage } from './features/coordinatorLead/UnassignedRequestsPage'
import { ClarificationResponsePage } from './features/organiser/ClarificationResponsePage'
import { EventDetailsPage } from './features/events/EventDetailsPage'
import { VenueStaffHomePage } from './features/venueStaff/VenueStaffHomePage'
import { VenueEditPage } from './features/venueStaff/VenueEditPage'
import { VenueCreatePage } from './features/venueStaff/VenueCreatePage'
import { VenueDetailsPage } from './features/venueStaff/VenueDetailsPage'
import { BookingDetailsPage } from './features/venueStaff/BookingDetailsPage'
import { BookingApprovalsPage } from './features/venueStaff/BookingApprovalsPage'
import { VenueCataloguePage } from './features/venueStaff/VenueCataloguePage'
import { VenueSchedulePage } from './features/venueStaff/VenueSchedulePage'
import { venueStaffNav } from './features/venueStaff/venueStaffNav'
import { AppShell as ConsoleShell } from './components/layout/AppShell'
import { coordinatorNav } from './features/coordinator/coordinatorNav'
import { attendeeNav } from './features/attendee/attendeeNav'
import { technicalSupportNav } from './features/technicalSupport/technicalSupportNav'
import { LegacyRedirect, LegacyReportRedirect, LegacyRequestRedirect } from './features/technicalSupport/LegacyRedirects'
import { TechnicalSupportDashboardPage } from './features/technicalSupport/dashboard/TechnicalSupportDashboardPage'
import { EquipmentRequestsPage } from './features/technicalSupport/requests/EquipmentRequestsPage'
import { EquipmentRequestDetailPage } from './features/technicalSupport/requests/EquipmentRequestDetailPage'
import { InventoryPage } from './features/technicalSupport/inventory/InventoryPage'
import { EquipmentDetailPage } from './features/technicalSupport/inventory/EquipmentDetailPage'
import { FaultyEquipmentPage } from './features/technicalSupport/faulty/FaultyEquipmentPage'
import { ReportProblemPage } from './features/technicalSupport/faulty/ReportProblemPage'
import { DamageReportDetailPage } from './features/technicalSupport/damage/DamageReportDetailPage'
import { AttendeeHomePage } from './features/attendee/AttendeeHomePage'
import { AttendeeEventDetailsPage } from './features/attendee/AttendeeEventDetailsPage'
import { AttendeeEventsPage } from './features/attendee/AttendeeEventsPage'
import { MyRegistrationsPage } from './features/attendee/MyRegistrationsPage'
import { RegistrationDetailsPage } from './features/attendee/RegistrationDetailsPage'
import { SafetyOfficerHomePage } from './features/safetyOfficer/SafetyOfficerHomePage'
import { SafetyReviewsPage } from './features/safetyOfficer/SafetyReviewsPage'
import { safetyOfficerNav } from './features/safetyOfficer/safetyOfficerNav'
import { SafetyEventDetailsPage } from './features/safetyOfficer/SafetyEventDetailsPage'

/** Route-level role gating. The role comes from the access token's `role`
 * claim (D19), so editing localStorage no longer promotes anyone: a tampered
 * token fails signature verification and every API call 401s. This gate is
 * therefore about not showing someone a console full of requests that would
 * all fail — the enforcement itself is D20's rules on the server. */
function useRoleGate(expected: Role) {
  const { role } = useSession()
  return role === expected
}

/** Where to send someone who asked for a console that isn't theirs, and what to
 * tell them when they get there (AU06).
 *
 * Signed in: their own console, which is the only one they can use — but the
 * attempted path travels with them so AppShell can say why they moved. Dropping
 * that message is a regression: AU06 is specifically "clear message on an
 * unauthorised action", and a silent redirect reads as the app misbehaving.
 * Signed out: the login page, which shows its own version of the notice. */
function redirectFor(role: Role | null, pathname: string): string {
  const denied = `access-denied=${encodeURIComponent(pathname)}`
  return role ? `${HOME_BY_ROLE[role]}?${denied}` : `/?${denied}`
}

function OrganiserRoutes() {
  const permitted = useRoleGate('organiser')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Event Organiser" items={organiserNav}>
      <Routes>
        <Route index element={<OrganiserHomePage />} />
        <Route path="requests" element={<MyRequestsPage />} />
        <Route path="requests/new" element={<EventRequestWizardPage />} />
        <Route path="requests/:requestId" element={<EventRequestWizardPage />} />
        <Route path="requests/:requestId/respond" element={<ClarificationResponsePage />} />
        <Route path="requests/:requestId/change" element={<ChangeRequestFormPage />} />
        <Route path="events/:eventId/change" element={<ChangeRequestFormPage />} />
        <Route path="requests/:requestId/changes" element={<ChangeRequestsPage />} />
        <Route path="events/:eventId" element={<EventDetailsPage />} />
        {/* Retired placeholder pages and unknown paths land on the dashboard. */}
        <Route path="*" element={<Navigate to="/organiser" replace />} />
      </Routes>
    </ConsoleShell>
  )
}

function CoordinatorRoutes() {
  const permitted = useRoleGate('coordinator')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Event Coordinator" items={coordinatorNav}>
      <Routes>
        <Route index element={<CoordinatorHomePage />} />
        <Route path="review-queue" element={<ReviewQueuePage />} />
        <Route path="my-events" element={<AssignedEventsPage />} />
        <Route path="requests/:requestId" element={<RequestReviewPage />} />
        <Route path="events/:eventId" element={<EventDetailsPage />} />
        <Route path="events/:eventId/reassignment-request" element={<EventReassignmentRequestPage />} />
        <Route path="requests/:requestId/changes" element={<ChangeRequestsPage />} />
        <Route path="change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
        <Route path="events/:eventId/venue-booking" element={<VenueBookingRequestPage />} />
        <Route path="venue-search" element={<VenueSearchPage />} />
        <Route path="events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
        <Route path="operational-issues" element={<OperationalIssuesPage />} />
        {/* Retired placeholder pages and unknown paths land on the dashboard. */}
        <Route path="*" element={<Navigate to="/coordinator" replace />} />
      </Routes>
    </ConsoleShell>
  )
}

function CoordinatorLeadRoutes() {
  const permitted = useRoleGate('coordinator-lead')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Event Coordinator Lead" items={coordinatorLeadNav}>
      <Routes>
        <Route index element={<CoordinatorLeadHomePage />} />
        <Route path="unassigned-requests" element={<UnassignedRequestsPage />} />
        <Route path="unassigned-requests/:requestId" element={<IncomingRequestReviewPage />} />
        {/* ECL-C7: the Lead can view any event for oversight (read-only). */}
        <Route path="events/:eventId" element={<EventDetailsPage />} />
        <Route path="assignments" element={<CoordinatorAssignmentsPage />} />
        {/* ECL-C4: every assigned active event, for workload before assigning. */}
        <Route path="active-events" element={<ActiveEventsPage />} />
        <Route path="reassignment-requests" element={<EventReassignmentRequestsPage />} />
        <Route path="requests/:requestId/changes" element={<ChangeRequestsPage />} />
        <Route path="change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
        {/* Unknown paths land on the dashboard. */}
        <Route path="*" element={<Navigate to="/coordinator-lead" replace />} />
      </Routes>
    </ConsoleShell>
  )
}

function VenueStaffRoutes() {
  const permitted = useRoleGate('venue-staff')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Venue Staff" items={venueStaffNav}>
      <Routes>
        <Route index element={<VenueStaffHomePage />} />
        <Route path="catalogue" element={<VenueCataloguePage />} />
        <Route path="catalogue/new" element={<VenueCreatePage />} />
        <Route path="catalogue/:venueId" element={<VenueDetailsPage />} />
        <Route path="bookings/:bookingId" element={<BookingDetailsPage />} />
        <Route path="booking-approvals" element={<BookingApprovalsPage />} />
        <Route path="availability-calendar" element={<VenueSchedulePage />} />
        <Route path="catalogue/:venueId/edit" element={<VenueEditPage />} />
        {/* Unknown paths land on the dashboard. */}
        <Route path="*" element={<Navigate to="/venue-staff" replace />} />
      </Routes>
    </ConsoleShell>
  )
}

function TechnicalSupportRoutes() {
  const permitted = useRoleGate('technical-support')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Technical Support" items={technicalSupportNav}>
      <Routes>
        <Route index element={<TechnicalSupportDashboardPage />} />
        {/* TS01/TS02/TS06: one page per request. */}
        <Route path="requests" element={<EquipmentRequestsPage />} />
        <Route path="requests/:requestId" element={<EquipmentRequestDetailPage />} />
        {/* TS03 blocks and TS09 damage reports, reported through one form. */}
        <Route path="faulty" element={<FaultyEquipmentPage />} />
        <Route path="faulty/new" element={<ReportProblemPage />} />
        <Route path="faulty/reports/:reportId" element={<DamageReportDetailPage />} />
        <Route path="inventory" element={<InventoryPage />} />
        <Route path="inventory/:equipmentId" element={<EquipmentDetailPage />} />
        {/* The routes before the rework, kept so old links still work. */}
        <Route path="availability" element={<LegacyRequestRedirect />} />
        <Route path="reservations" element={<LegacyRequestRedirect />} />
        <Route path="status" element={<LegacyRedirect to="/technical-support/inventory" />} />
        <Route path="damaged-equipment" element={<LegacyRedirect to="/technical-support/faulty" />} />
        <Route path="damaged-equipment/new" element={<LegacyRedirect to="/technical-support/faulty/new" />} />
        <Route path="damaged-equipment/:reportId" element={<LegacyReportRedirect />} />
      </Routes>
    </ConsoleShell>
  )
}

function AttendeeRoutes() {
  const permitted = useRoleGate('attendee')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Attendee" items={attendeeNav}>
      <Routes>
        <Route index element={<AttendeeHomePage />} />
        <Route path="browse" element={<AttendeeEventsPage />} />
        <Route path="events/:eventId" element={<AttendeeEventDetailsPage />} />
        <Route path="my-registrations" element={<MyRegistrationsPage />} />
        <Route path="my-registrations/:registrationId" element={<RegistrationDetailsPage />} />
        <Route path="register" element={<Navigate to="/attendee/browse" replace />} />
        {/* Unknown paths land on the dashboard. */}
        <Route path="*" element={<Navigate to="/attendee" replace />} />
      </Routes>
    </ConsoleShell>
  )
}

function SafetyOfficerRoutes() {
  const permitted = useRoleGate('safety-officer')
  const { pathname } = useLocation()
  const { role } = useSession()
  if (!permitted) return <Navigate to={redirectFor(role, pathname)} replace />
  return (
    <ConsoleShell navLabel="Safety Officer" items={safetyOfficerNav}>
      <Routes>
        <Route index element={<SafetyOfficerHomePage />} />
        <Route path="reviews" element={<SafetyReviewsPage />} />
        <Route path="events/:eventId" element={<SafetyEventDetailsPage />} />
        <Route path="*" element={<Navigate to="/safety-officer" replace />} />
      </Routes>
    </ConsoleShell>
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
      <Route path="/coordinator-lead/*" element={<CoordinatorLeadRoutes />} />
      <Route path="/venue-staff/*" element={<VenueStaffRoutes />} />
      <Route path="/technical-support/*" element={<TechnicalSupportRoutes />} />
      <Route path="/attendee/*" element={<AttendeeRoutes />} />
      <Route path="/safety-officer/*" element={<SafetyOfficerRoutes />} />
      {/* EO03/EC-NEW3: notification links; opens the reader's own console. */}
      <Route path="/change-requests/:requestId" element={<ChangeRequestRedirect />} />
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
