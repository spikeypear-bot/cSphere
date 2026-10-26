# Venue Staff audit remediation — 26 October 2026

Repository: `C:/cSphere`. This work builds on the pre-existing modified and
untracked implementation reviewed in the comprehensive audit. Earlier working
changes were retained. No commit, push, deployment, live backlog edit, or
application-database migration was performed.

## Implemented scope

| Finding | Result | Regression evidence |
|---|---|---|
| F01 replacement integrity | Submission and approval check all active members of the requirement group while holding the event lock. Rejected ancestors cannot branch around pending or approved descendants. The source commitment remains valid while its one successor awaits approval. Existing malformed pending branches must be resolved before approval. | Sequential ancestor branching; concurrent submissions; concurrent legacy-branch approvals; original-plus-pending swap and independent room tests. |
| F02 cancellation locking | Cancellation acquires every participating active venue lock in the same distinct, sorted UUID order used by batch requests and replacement approvals, before releasing holds or updating bookings. | Real PostgreSQL lock contention against a batch and against replacement approval; bounded completion, independent competing event, all holds released; explicit rollback retains bookings, holds, history and notification work. |
| F03 superseded reviews | Backend decisions and response actionability share one currency calculation. Newer requirement reviews, later assessments, changed reservation windows/durations, or later timing revisions make a review historical. The UI suppresses obsolete forms even if an older response says actionable. History and historical coordinator identity remain. | Two approved changes followed by stale direct API decision returns 409; no stale outcome notification; timing revision blocks decision; historical UI and current-versus-historical EC presentation. |
| F04 effective snapshots | Before/after snapshots apply each non-null room override to the corresponding inherited event value. Empty lists and empty written overrides are preserved. Semantic comparison suppresses reviews/notices for unaffected rooms. Event timing changes remain explicit review triggers; saved booking windows are not moved. | Overridden room receives no attendance-only review; inherited sibling receives its review; mixed inherited text and overridden attendance/empty facility/accessibility lists have exact snapshots. |
| F05 approval notices | Successful normal approval and hold conversion enqueue `venue_booking_approved` for the coordinator assigned at the decision, with event and booking identifiers. The conditional status update and existing transactional outbox prevent duplicate successful decisions and rollback notices. V51 adds the notification enum and shape without rewriting old migrations. | Reassignment before decision, both approval paths, duplicate attempt, rollback, identifiers and exactly one outcome per booking. Existing hold test now asserts the new approval notices while continuing to reject unrelated notices. |
| F06 calendar | Schedule responses include timed operational issues using the same half-open overlap boundaries as availability. Calendar entries display those blockers and explain booking/hold restriction conflicts, including setup-only overlaps. Informational issues are omitted from blocking entries. Existing booking-pair conflict indicators are preserved separately. | PostgreSQL schedule/availability parity; touching intervals; setup-only restriction; held period; informational exclusion; expiry; rendered warning details and accessible conflict label. |
| G01/G02 staff presentation | Current EC name/email are provided separately from historical review approval identity. Approval queues, associated booking lists and unavailability previews show event/booking identifiers. Queues expose effective room facilities/accessibility, preserving explicit empties. Existing effective detail requirements and independent decisions remain. | Reassignment/current-versus-historical review data; queue IDs/contact/effective requirements; same-name preview bookings distinguished by both identifiers; existing detail and multi-venue suites. |
| F09 golden path | The browser journey now requests and approves two rooms, checks that the first decision leaves the other pending, verifies one safety item for each booking ID plus the six standard items, blocks premature safety approval, completes every named item, and verifies both organiser arrangements. | Updated real browser golden path; existing backend per-booking safety invalidation tests. |
| F10 rejection bounds | Rejection reasons over 2000 trimmed characters fail validation before mutation. Audit summaries are bounded after composing labels, venue names and notes; original rejection/alternative text remains in its own fields. | 2000/2001 boundary, clear 422 API response, maximum reason plus maximum alternative note succeeds with bounded activity summaries and unchanged sibling booking. |

Shared role-wide Venue Staff access remains. No individual staff ownership,
new EO/EC room-change workflow, Attendee/TSS feature, or optional booking-history
UI was added.

## Files changed in this task

Paths below are relative to the repository. Some files were already modified or
untracked before this task; this list identifies files edited here, not ownership
of every difference from HEAD.

Backend production:

- `backend/src/main/java/com/example/connect_sphere/changerequest/VenueRequirementChangeService.java`
- `backend/src/main/java/com/example/connect_sphere/changerequest/VenueRequirementReviewService.java`
- `backend/src/main/java/com/example/connect_sphere/event/service/EventLifecycleService.java`
- `backend/src/main/java/com/example/connect_sphere/notification/entity/NotificationType.java`
- `backend/src/main/java/com/example/connect_sphere/notification/service/NotificationService.java`
- `backend/src/main/java/com/example/connect_sphere/venueavailability/AvailabilityModels.java`
- `backend/src/main/java/com/example/connect_sphere/venueavailability/AvailabilityRepository.java`
- `backend/src/main/java/com/example/connect_sphere/venueavailability/AvailabilityService.java`
- `backend/src/main/java/com/example/connect_sphere/venuebooking/dto/VenueBookingDto.java`
- `backend/src/main/java/com/example/connect_sphere/venuebooking/service/BookingPlanService.java`
- `backend/src/main/java/com/example/connect_sphere/venuebooking/service/VenueBookingRequestService.java`
- `backend/src/main/java/com/example/connect_sphere/venuebooking/service/VenueBookingService.java`
- `backend/src/main/resources/db/migration/V51__venue_booking_approval_notification.sql` (new)
- `backend/src/main/resources/db/migration/SCHEMA.md`

Backend tests:

- `backend/src/test/java/com/example/connect_sphere/changerequest/VenueRequirementReviewFlowTest.java`
- `backend/src/test/java/com/example/connect_sphere/event/service/EventLifecycleCancellationTest.java`
- `backend/src/test/java/com/example/connect_sphere/venue/controller/VenueControllerTest.java`
- `backend/src/test/java/com/example/connect_sphere/venuebooking/VenueHoldFlowTest.java`
- `backend/src/test/java/com/example/connect_sphere/venuebooking/VenueStaffAuditRegressionTest.java` (new)

Frontend production:

- `frontend/src/features/notifications/NotificationBell.tsx`
- `frontend/src/features/venueStaff/BookingApprovalsPage.tsx`
- `frontend/src/features/venueStaff/BookingDetailsPage.tsx`
- `frontend/src/features/venueStaff/VenueDetailsPage.tsx`
- `frontend/src/features/venueStaff/VenueRequirementReviews.tsx`
- `frontend/src/features/venueStaff/VenueSchedulePage.tsx`
- `frontend/src/features/venueStaff/VenueSchedulePage.css`
- `frontend/src/features/venueStaff/VenueUnavailabilityPanel.tsx`
- `frontend/src/features/venueStaff/venueCalendar.ts`
- `frontend/src/types/notification.ts`
- `frontend/src/types/venueBooking.ts`
- `frontend/src/types/venueRequirementReview.ts`

Frontend/browser tests:

- `frontend/src/features/venueStaff/BookingApprovalsPage.test.tsx`
- `frontend/src/features/venueStaff/VenueRequirementReviews.test.tsx`
- `frontend/src/features/venueStaff/VenueSchedulePage.test.tsx`
- `frontend/src/features/venueStaff/VenueUnavailabilityPanel.test.tsx`
- `frontend/src/features/venueStaff/venueCalendarAudit.test.ts` (new)
- `frontend/e2e/golden-path.spec.ts`

Documentation: this report and the V51 schema entry above. The canonical
`docs/test-traceability.md` was not edited by this task.

## Verification

All database checks use isolated local PostgreSQL 18.3 databases, not the
application database. Java 25 and the installed Mockito startup agent were used.
Frontend tests used bundled Node 24 and Vitest thread workers; the initial fork
worker attempts were stopped because they did not progress reliably here.

- Relevant backend audit regression set: **44 classes, 720 tests, zero failures/errors/skips**.
  This includes PostgreSQL integration/concurrency, booking/read/authorisation,
  hold, unavailability, operational issue, review, notification, lifecycle,
  reassignment and per-booking safety coverage.
- Full frontend: **66 files, 696 tests passed**.
- Frontend production build and ESLint passed. Vite retains the bundle-size
  advisory for a chunk over 500 kB.
- Playwright: **19 browser journeys passed**, including the two-room golden
  path, holds, requirement reviews, reassignment, unavailable-period release,
  live suitability, and desktop/mobile presentation.
- Story traceability: zero untraced test files. The script separately reports
  six High-priority stories without tests; that is a repository-wide coverage
  advisory, not a failure of this scoped remediation.

Final targeted rechecks: **19 PostgreSQL tests passed** after the final currency
calculation and fixture-cleanup edits; **15 calendar component tests passed**
after the final accessible warning change. Final backend packaging, frontend
build and lint also passed. The final browser rerun against those packaged
artifacts passed **all 19 journeys in 1.9 minutes**. Temporary test services were
stopped after verification; the user's existing development services were left
running. Logs are under `C:/cSphere/logs/vs-fixes-*`.

Evidence:

- [Backend per-suite counts and result summary](../logs/vs-fixes-results.json)
- [Full relevant backend run](../logs/vs-fixes-backend-regression-final.log)
- [Final targeted PostgreSQL recheck](../logs/vs-fixes-final-targeted.log)
- [Full frontend suite](../logs/vs-fixes-frontend-final.log)
- [Final calendar recheck](../logs/vs-fixes-calendar-final.log)
- [Build](../logs/vs-fixes-build-final.log) and [lint](../logs/vs-fixes-lint-final.log)
- [Browser suite](../logs/vs-fixes-browser-final.log)
- [Traceability](../logs/vs-fixes-traceability-final.log)

### Failures found and corrected during verification

- Cancellation unit tests needed the new venue-lock collaborator and real venue
  IDs in their booking fixtures.
- The hold schedule regression's old assertion of zero notifications needed to
  account for the newly required approval outcomes. It now asserts exactly two
  approval notices and zero other notices.
- A catalogue reservation-preservation test used the current time and assumed
  the fixture's Monday venue was closed. During this Monday afternoon run it
  was open. A fixed future Tuesday window makes the intended closed-hours
  condition deterministic, preserving the original reservation assertions.
- New legacy-branch fixture initially violated the existing direct-child unique
  index; it now reproduces branching across different rejected ancestors.
- New committed concurrency fixtures now explicitly remove their own bookings
  before events during cleanup, respecting the existing non-cascading FK.

## Acceptance limits

Passing automated checks supports final Venue Staff acceptance testing; it does
not replace human acceptance or production deployment qualification.

- No production dataset was inspected for already-existing branched approved
  requirements. Such historical data is retained; the fix rejects further
  conflicting submissions/approvals rather than silently deleting commitments.
- The audit's U01 policy question remains: safety fingerprints still include
  event-level context even when a room overrides that field. Effective room
  review notices are fixed; safety invalidation policy was not expanded here.
- The separate EO/EC duration-proposal and authorisation policy questions from
  the audit remain outside this requested defect scope.
- This is relevant backend coverage, Chromium E2E and component testing, not a
  complete all-role product certification, production migration rehearsal on
  historical datasets, sustained-load test, or process-crash recovery test.

No known failure in the requested VS scope remains in the completed regression
runs. Final acceptance should cover stale review tabs, replacement ancestors,
multi-venue cancellation with active holds, current EC contact/outcome notices,
room-specific snapshots, and timed operational calendar restrictions.

**Verdict: ready for final Venue Staff acceptance testing within this requested
scope.** Human acceptance and deployment approval remain separate steps.
