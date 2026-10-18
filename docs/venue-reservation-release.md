# VS25 — Release venue reservation

## Model and scope

`VenueBookingRecord` and `VenueBooking` are two entity views of `venue_bookings`,
not separate reservations. An `approved` row blocks availability using its saved
event, setup and turnaround times. Pending rows appear as tentative calendar
entries but do not block approval. `rejected`, `cancelled` and `changed` rows are
history and are excluded by `AvailabilityRepository` from active schedules.
No migration, new status, endpoint or manual release operation is needed.

## Workflows

- Rejection: the existing Venue Staff pending-only rejection stores the reason
  and changes status atomically. Pending requests have no committed reservation
  to release; their tentative schedule entries disappear.
- Cancellation: the existing assigned-coordinator endpoint now accepts pending
  and approved bookings. Completed/cancelled events and originals with pending
  replacements cannot be changed. Repeated cancellation returns a conflict
  without another mutation or notification. An approved cancellation uses
  `EventLifecycleService.returnToPlanning` in the same transaction so a safety
  clearance/confirmation cannot survive the loss of its venue arrangement.
  Timeline entries retain the actual previous booking status.
- Supersession: the existing `replacesBookingId` workflow supports replacements
  for bookings affected by recorded venue unavailability. Approval validates the
  original, locks both venues in UUID order, checks the replacement against all
  occupancy except its own original, marks the original `changed`, and approves
  the replacement atomically. Unavailability and operational restrictions still
  apply. Pending/rejected replacements leave the original intact.

General organiser amendments are not implemented by this change. They must use
an approved change workflow when that separate feature is available; VS25 does
not create a new amendment system or broaden replacement eligibility.

## UI and consistency

The existing cancellation confirmation in `EventDetailsPage` now handles
approved bookings and selects one booking at a time. It refreshes bookings,
event status, readiness and history on success and retains errors on failure.
Venue reads (including the calendar) fetch fresh data on navigation and window
focus, as well as through existing refresh controls. There is no live push
subscription: a continuously focused calendar uses its Refresh control for
changes made by other users.

Transactions retain the event-first, venue-second locking order. The status
change itself releases the entire occupied period; no interval deletion is
performed, so unrelated bookings and unavailable periods remain intact.

## Verification

### Acceptance review — 16 October 2026

This review covers the current working tree, including the uncommitted VS25
changes. It is not a claim that these changes have been committed or deployed.
Reservation status is the booking status in `venue_bookings`; there is no
separate reservation record or release flag.

| # | Acceptance criterion | Assessment and evidence |
| --- | --- | --- |
| 1 | Release when rejected | Met under the current model. `VenueBookingService.reject` changes only a pending request to `rejected`; it disappears from active schedules. Pending requests are non-blocking tentative entries, so rejection does not release an exclusive hold. `VenueBookingRejectionTest` verifies status, retained data and repeat protection; `VenueUnavailabilityTest.buffersBoundariesStatusesAndVenueScoping` verifies inactive status filtering. |
| 2 | Release when cancelled | Met. `VenueBookingRequestService.cancel` accepts pending/approved bookings and sets `cancelled` transactionally. `cancellationReleasesBuffersAndPreservesOtherOccupancyAndHistory` verifies removal of the entire occupied interval; `VenueBookingRequestFlowTest` verifies the authenticated endpoint. |
| 3 | Release the previous reservation after an approved change | Met for the existing unavailability replacement workflow only. Approval changes the specified original to `changed` and the replacement to `approved` in one transaction. `replacementApprovalChangesOnlyOriginalStatus` and `sameVenueReplacementExcludesOnlyOriginalAndReleasesItsOldPeriod` cover this. General booking amendments are not implemented and cannot be signed off by these tests. |
| 4 | Release only the affected reservation | Met. Mutations target booking IDs, and cancellation validates event membership and coordinator ownership. Replacement excludes only its validated original from conflict checking. Unrelated bookings remain active in the cancellation/replacement tests. |
| 5 | Released occupancy no longer blocks compatible requests | Met. Conflict checks include approved bookings, excluding rejected/cancelled/changed rows. `concurrentCancellationAndApprovalKeepOneConsistentReservation` verifies that a contender can be approved after cancellation. Other approved bookings, opening hours, buffers and recorded restrictions still apply. |
| 6 | Update venue availability | Met. Availability is derived from current booking statuses, not a separate cached reservation table. Backend schedule tests verify released entries disappear; the frontend calendar test verifies refresh removes released occupancy while retaining other entries. |
| 7 | Retain the request and updated status | Met. Release updates rather than deletes the booking row. Rejection tests compare retained fields; replacement tests preserve original notes; cancellation tests retain the original in event booking history. |
| 8 | Prevent duplicate release | Met. Pending-only rejection/approval and active-only cancellation reject repeated terminal-state transitions. Event/venue locks serialize competing decisions. Repeated rejection/cancellation and concurrent decision tests cover these guards. A repeat is a conflict, not a second successful release. |
| 9 | Preserve other confirmed bookings and active reservations | Met for booking occupancy. Updates are scoped to the selected booking and, for replacement, its original. Tests retain unrelated approved occupancy, tentative entries and unavailable periods. Cancelling an approved booking intentionally returns its associated event to Planning when required; this does not release its other booking rows. |
| 10 | Display the latest status after release | Met after a successful action/read. Staff decisions use the returned booking; coordinator cancellation reloads bookings, event/readiness and history. Cancelled/changed entries appear under Released bookings. Calendar reads refresh on navigation, focus or manual Refresh; there is no live push to a continuously focused page. `BookingDetailsPage`, `EventPlanningPanels` and `VenueSchedulePage` tests cover these behaviors. |
| 11 | Show an appropriate error and avoid partial updates if release fails | Partially met. Transaction rollback is tested for rejection, cancellation and replacement approval, including failure after the original replacement booking has been changed. Staff rejection/approval maps database and transaction failures to HTTP 503 with a retry message. Coordinator cancellation has no equivalent database/transaction exception handler; the UI may show only `Request failed (500)`. Its frontend failure test covers a business-rule 409, not an injected database failure through the cancellation endpoint. |

Outstanding before unconditional acceptance: provide and verify a useful
cancellation database-failure response through the authenticated endpoint and
UI. If criterion 3 is intended to cover general approved amendments, that
workflow also remains outside the verified implementation. If pending requests
are intended to reserve time exclusively, criterion 1 requires a different hold
model from the current non-blocking tentative entries.

`VenueUnavailabilityTest` covers cancellation of approved occupancy with buffers,
history, repeated attempts, unrelated occupancy, same-venue replacement,
replacement failure rollback, cancellation failure rollback and concurrent
cancellation/approval. Existing tests cover rejection, pending cancellation,
pending/rejected replacements, calendar filtering, authorization and approval
concurrency. `VenueBookingRequestFlowTest` covers approved cancellation through
the authenticated endpoint. Frontend tests cover pending/approved cancellation
and calendar refresh retaining unrelated entries.

## Changed files

| File | Purpose |
| --- | --- |
| `VenueBookingRequestService.java` | Cancel approved bookings and reset event readiness transactionally; preserve the previous status in history. |
| `VenueBookingService.java` | Validate and exclude only the original reservation during replacement approval. |
| `EventVenueBookingController.java` | Update the existing cancellation endpoint documentation. |
| `EventDetailsPage.tsx` | Reuse cancellation confirmation, select one booking, refresh dependent views and display released history. |
| `useVenueRead.ts` | Refresh booking and calendar reads on window focus. |
| `VenueBookingRequestFlowTest.java`, `VenueBookingRequestServiceTest.java`, `VenueUnavailabilityTest.java` | API, service, rollback, occupancy and concurrency coverage. |
| `EventPlanningPanels.test.tsx`, `VenueSchedulePage.test.tsx` | Cancellation, failure, history and calendar refresh coverage. |
| `venue-booking-api.md`, this document | Describe replacement conflict exclusion and VS25 behavior/scope. |

Earlier validation on 16 October 2026 recorded 131 focused backend tests passing
against an isolated PostgreSQL database. The development database's conflicting
local V23/V24 timing migrations were subsequently reconciled separately after a
backup; backend startup then validated all 28 migrations. That startup repair
is separate from the release acceptance tests.

Acceptance-review rerun on 16 October 2026: **131 backend tests passed, zero
failures/errors/skips**, using a newly created isolated PostgreSQL database and
the five backend suites named above. **74 frontend tests passed** across
`BookingDetailsPage.test.tsx`, `BookingApprovalsPage.test.tsx`,
`EventPlanningPanels.test.tsx` and `VenueSchedulePage.test.tsx`. Local output is in
`logs/release-acceptance-backend.log` and `logs/release-acceptance-frontend.log`
(ignored by Git). These are focused automated checks, not a new live-browser
end-to-end run or evidence that the identified cancellation error gap is fixed.
