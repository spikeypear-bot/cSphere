# VS17 — approved venue requirement changes

## Scope and verified reuse

Story ID VS17 was confirmed by the user on 24 October 2026. VS15 remains the
Venue Availability Calendar. The live backlog could not be accessed and this
checkout does not contain the original Week 1/4/7 customer documents. Those
source documents remain an explicit verification gap; this implementation uses
the supplied brief and current code. No backlog was edited.

VS17 now includes approved-change tracking, booking review history and indicator,
staff outcome submission, mandatory comments for coordinator action, coordinator
outcome visibility/notifications and regression coverage. Final venue seed data
and the complete thirteen-flow manual audit remain outside these implementation
steps.

| Concern | Reused code and behaviour |
|---|---|
| Approval and assignment | `ChangeRequestService.approve`: existing locked, assigned-coordinator approval and impact acknowledgements |
| Historical differences | `ChangeFields`: original/proposed JSON and semantic comparison via `ActivityService.sameValue` |
| EO/EC audit | Existing `change_approved` activity still commits with approval; `ChangeLogRecorder` remains the event activity adapter |
| Active bookings | Existing `pending` and `approved` labels, as agreed by the user; each booking gets its own review |
| Suitability | Existing `ChangeImpactService` continues to check capacity, accessibility, facilities, hours, operational issues, unavailable periods and hold coverage |
| Occupancy | Existing booking windows and setup/turnaround snapshots, independent of equipment loan periods |
| Notifications | Existing `notifications` storage, recipient-scoped reads/read markers, bell and `/venue-staff/bookings/{id}` route |
| Seeds | Existing opt-in `backend/dev/seed` SQL and PowerShell fixtures; unchanged in this slice |
| Tests | Existing change-request, impact, audit, notification and booking suites, plus VS17-specific unit, PostgreSQL integration and bell tests |

## Persistence and transaction boundary

V34 adds `venue_requirement_reviews`, referencing the approved change, booking,
event, venue, coordinator and approving user. Each review snapshots the event
name, venue address, coordinator identity/contact, approval timestamp, supported
venue requirements before/after, actual reserved window and saved durations.
`pending` is review state, independent of booking status and notification read state.
V35 extends review state to `suitable` and `action_required`, with a reviewer,
review timestamp, comment and captured check results. Historical snapshots and
decided outcomes cannot be updated or deleted (database trigger).

Changes to start/end, attendance, venue requirement text, accessibility or required
facilities trigger review. Equivalent instants and reordered lists do not. Changes
limited to description, purpose, equipment or registration do not trigger it.
There is no structured event layout field: layout requests within venue requirement
text trigger manual review. No new organiser fields or booking statuses are added.

The recorder uses MANDATORY propagation inside the existing approval transaction.
It writes reviews and VS17 notifications before commit, without external delivery.
Database readers only see them after commit. A failure rolls back approval,
review snapshots, audit and notifications together; a 503 response allows retry.
Other notification types keep their existing transaction semantics. This follows
the existing hold-expiry/reminder atomic notification precedent rather than the
older change-request best-effort after-commit delivery.

The existing change/event locks serialize approval. A unique `(change_request_id,
booking_id)` constraint and replay guard prevent repeat reviews; a unique
`(requirement_review_id, recipient_user_id)` index protects notification delivery.
No old notifications are backfilled and no previously applied migration is edited.

Date changes materialise missing legacy booking start/end values from the old
event window, preserving exactly the same reservation. This applies to active
and historical legacy bookings because both otherwise inherit the changed event
time. Existing explicit windows, booking status, holds and duration snapshots are
unchanged. Re-planning and safety transitions still use the existing approval rules.

## Access and audit boundaries

There is no staff-to-venue ownership table. The current booking implementation
permits Venue Staff role-wide access, so VS17 recipients are all current `vs`
users. No new access scope or endpoint is introduced. Notifications remain private
to their recipient. The full EO/EC timeline is not opened to Venue Staff: reviews
carry only the venue-related historical requirements, avoiding unrelated organiser
details. The existing EO/EC audit entry is reused without changing its audience.

## Documentation conflicts found

Some older context/roadmap passages still describe role-selector login, missing
audit history, and event-derived booking times. Current JWT security, activity
history and independent booking windows supersede those descriptions. Refer to
current code and the latest migration sections rather than those old summaries.
The booking-write entity's old comment also described event-derived times; it
has been corrected in this slice.

## Review workflow and API (V35)

| Endpoint | Permission | Behaviour |
|---|---|---|
| `GET /api/venue-bookings/{bookingId}/requirement-reviews` | Venue Staff | Historical changes, outcome, current actual window/durations and live checks |
| `POST /api/venue-bookings/{bookingId}/requirement-reviews/{reviewId}/decision` | Venue Staff | `{ "outcome": "suitable" or "action_required", "note": "..." }` |
| `GET /api/events/{eventId}/venue-reviews` | Currently assigned Event Coordinator | Outcomes and outstanding reviews across independent venue bookings |

The staff booking page and coordinator venue-request page render the same review
history panel. `Review Required` depends on pending review records, never bell read
state. Marking the notification read does not complete a review; completing a
review does not mark the original notification read. Notifications deep-link to
the affected staff booking or coordinator venue-request page.

History shows approved previous/new requirements, approval time and coordinator
at approval; current reserved times and durations are labelled separately from
the immutable reservation snapshot captured when the change was approved.
Checks are explicitly current, not historical. The decision saves the exact checks
used at that time, so subsequent venue/event changes do not rewrite the outcome.

The review uses the existing venue suitability/availability functions at the
booking's actual reserved interval, including setup and turnaround. Only that
booking's own reservation is excluded; other bookings, including another booking
for the same event, and active holds remain conflicts. Operating hours,
unavailability, operational issues, capacity, facilities and accessibility are
checked. Layout and written requirements need staff judgement. A booking outside
the current event's schedule is flagged for coordinator attention.

Submission acquires event, venue and review locks in that order. Only active
pending/approved bookings for a future-ending, non-cancelled/non-completed event
can be reviewed. A decided review cannot be overwritten (409 on stale/repeated
submission). Live checks are recalculated under the locks: `suitable` is refused
if checks require attention; staff can record `action_required` with a required,
nonblank comment of at most 2000 characters. Suitable reviews allow an optional
comment with the same limit. Client and server validate these rules.

The outcome, EC/VS-audience `venue_requirements_reviewed` audit entry and
`venue_requirement_reviewed` notification commit together. The notification goes
to the event's **current** coordinator, even after reassignment; original
coordinator details in the approval snapshot remain unchanged. Review actions do
not change event lifecycle/safety state, booking status/window/durations, holds,
equipment reservations or other venue bookings. `action_required` communicates a
follow-up; existing coordinator replacement and re-planning actions remain the
mechanisms for changing arrangements.

API errors use existing 401/403/404/409/422 conventions and a retryable 503 for
persistence failures. The UI handles loading, failed reads with retry, failed saves
with input retained, and 409 refresh after another staff decision. It polls every
15 seconds and refreshes on window focus. Inactive booking history remains readable
but has no decision form. Review state is independent for every approved change
and booking; resolving an older review does not resolve a newer one.

## Validation

The current backend run passed **515** unit/integration/regression tests against
isolated PostgreSQL 18, including approval/rejection, cancellations and release,
replacement, tentative holds, setup/turnaround conflicts, change-request audit,
safety lifecycle and equipment periods. V1–V35 migrated successfully on a fresh
database. The wider frontend run passed **257** cases across 19 files. The real Chromium
staff-to-coordinator journey passed against the packaged application. Lint and
traceability passed (152 test files; zero untraced files).
The frontend production build and backend packaging passed. Local
`vs17-review-*.log` files record detailed results.

`VenueReviewWorkflowTest` covers outcomes and mandatory comments, validation
boundaries, read/review independence, wrong-role and wrong-assignment access,
cross-booking tampering, current coordinator reassignment, duplicate decisions,
terminal booking rejection, immutable snapshots, live suitability and rollback
when notification storage fails. It compares the complete booking and event
records before/after a review to protect reservations and lifecycle.
`VenueRequirementReviews.test.tsx` covers historical/current display, submission,
coordinator read-only outcomes, errors/retries and stale decisions. The existing
booking and queue fixtures were extended for the new read endpoint; their
assertions remain intact. A hold regression caught and fixed a duplicate sibling
key between the new review panel and existing hold panel.

`frontend/e2e/venue-requirement-review.spec.ts` passed and exercises the real packaged app:
staff follow the notification, confirm that reading it leaves review required,
submit coordinator action, and the assigned coordinator sees the outcome and
notification. It checks that the booking status and timing remain unchanged.
No numbered ACs are invented; tests use the user-confirmed VS17 story ID.


## Result-focused suitability summary (25 October 2026)

Review responses now include a live `suitability` summary with explicit capacity,
accessibility, facilities, opening hours, booking conflicts, unavailable periods,
tentative hold conflicts and operational issue results. Booking and hold conflicts
are distinguished using the existing availability service at the buffered reserved
window. Current written requirements and supported venue layouts are supplied
separately for staff assessment; layout text is never automatically marked suitable.
The UI shows a results table, an occupied-period summary and a requirement/layout
comparison in place of instructional paragraphs. Historical saved checks and the
existing decision validation remain unchanged. An unavailable summary is labelled
as unavailable rather than interpreted as successful checks. The New requirements
column remains bold.

Summary verification: 26 backend tests (including 3 focused summary tests), 58 frontend tests, production frontend build, lint and traceability passed. Tests ran against an isolated disposable PostgreSQL database.
