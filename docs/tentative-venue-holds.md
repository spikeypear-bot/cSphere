# VS-C3 — Time-limited tentative venue holds

Venue Staff place a hold from an existing pending booking's details. The booking
already identifies its venue, event, requested start/end, coordinator and saved
setup/turnaround durations. Staff enter the expiry in Singapore time. Pending
requests without an active hold remain non-blocking.

For ready-to-use local scenarios and a UI walkthrough, see
[VS-C3 demo data and manual testing](vsc3-manual-testing.md). The optional
`backend/dev/seed/vsc3-seed.ps1` creates labelled fixtures through the real APIs,
using future dates and relative expiry deadlines, and retains old batches.

## Model and rules

V29 adds `venue_booking_holds`, a child history table of `venue_bookings`, not
another booking/reservation entity. Each attempt retains an ID, the complete
occupied interval, expiry, creator, creation time, end time and lifecycle state.
Only one persisted active attempt per booking is allowed. Before re-holding,
overdue attempts are settled transactionally. Old attempts are never overwritten.

States: `active`, `expired`, `released`, `converted`. Booking statuses remain
unchanged. Expiry/release leaves the request pending. Conversion approves the
same booking, consumes its hold and retains both histories atomically. A later
ordinary approval after expiry/release is a fresh availability decision; it is
not conversion of the ended hold. The explicit conversion endpoint rejects it.

The occupied interval uses the booking's window (legacy rows fall back to the
event window), less its saved setup duration and plus its saved turnaround.
Hold snapshots already include buffers; they must never receive another buffer.
Conversion checks that the booking still matches its hold's occupied interval.
Timing edits require releasing an active hold first.

Expiry must be strictly after server time. The existing booking's positive
interval is validated again. No new future-start restriction or expiry-before-
start/end restriction is imposed. Creating a hold requires a pending booking and
an event in Planning or Safety Changes Requested. It does not confirm an event
or bypass the safety workflow. Standalone/anonymous holds are not supported.

## Availability, concurrency and automatic expiry

Blocking requires an active hold, `expires_at > server now`, a pending parent
booking and an event that is neither cancelled nor completed. Availability,
venue search, coordinator selection/submission, approval and timing checks use
the same hold repository and existing half-open overlap rule. Holds from the
same event are not broadly excluded; only the specific booking is excluded
while converting. Approved bookings and configured availability rules retain
their existing behaviour.

Writers acquire the event lock then venue locks, following the approval workflow.
Multi-venue replacement approval retains the existing sorted venue locking.
Conversion checks the deadline again immediately before a conditional transition;
that transition is its decision instant. Failed approval rolls back consumption.
Release also conditionally requires an unexpired hold. Repeated release returns
409. Repeated scheduled expiry is a no-op.

`VenueHoldExpiryJob` processes up to 100 overdue holds per run, with a separate
transaction for each. `venue.holds.expiry-delay-ms` defaults to 30000. Failures
are logged and retried on the next run. Immediate logical expiry never depends
on successful cleanup, audit or notification delivery.

The persisted expiry transition, notification and activity record share one
transaction. A failure rolls them back for retry, avoiding duplicate notifications.
Notifications go to the event's current assigned coordinator. Automatic activity
entries show ConnectSphere/system; their required user FK retains the initiating
staff member for traceability. Events created outside the request workflow have
no request timeline, consistent with existing activity behaviour; their hold
attempt history is still retained.

## Expiry reminders (EC-C3)

The event's coordinator is reminded once before each hold expires, so they can
press for confirmation or look for another venue.

- **Window.** `venue.holds.reminder-lead` (ISO-8601 duration, default `PT24H`,
  in `application.properties`; environment `VENUE_HOLDS_REMINDER_LEAD`). A hold
  is due when it is active, its booking is pending, its event is neither
  cancelled nor completed, and `now < expires_at <= now + lead` — a hold exactly
  at the edge is due. A hold placed with less time left than the lead is due at
  the next run; one that has already expired gets only the expiry notice.
- **Job.** `VenueHoldReminderJob` runs on the expiry job's schedule
  (`venue.holds.expiry-delay-ms`, default 30000) and processes up to 100 due
  holds per run, each in its own transaction. Failures are logged and retried.
- **Once per hold.** V31 adds `venue_booking_holds.reminded_at`.
  `VenueHoldService.remind` takes the same event-then-venue locks as expiry,
  re-checks the hold, sets `reminded_at` with a conditional update and writes the
  `venue_hold_expiring` notification in the same transaction. Repeated or
  concurrent runs send one reminder; a failed insert rolls back the mark so the
  next run retries. A new hold on the same booking is a new attempt with its own
  reminder. Nothing else changes: hold, booking and event keep their state.
- **Recipient.** The event's coordinator at send time (`events.coordinator_id`),
  so after a reassignment the previous coordinator receives no reminders or
  expiry notices for that event.
- **Message.** Names the event, the venue and the expiry in Singapore time
  (e.g. "2 Mar 2027, 4:00 AM (Singapore time)") and links to
  `/coordinator/events/{eventId}/venue-booking`. The bell titles it "Tentative
  venue hold expiring soon for "{event}"". There is no notification when a hold
  is converted (out of scope).
- **Screen.** Each hold DTO carries `reminderDueAt` (expiry minus the lead). The
  coordinator's venue booking page marks an active hold **Expiring soon** once
  server time reaches it, timed from `serverTime` like the expiry deadline. An
  expired hold shows **Expired** and never as an approved booking.
- **Expiry notice.** The existing VS-C3 notice (above) now also says the venue
  is no longer held.

Covered by `VenueHoldReminderFlowTest` (real PostgreSQL, mutable server clock),
`NotificationServiceTest`, `NotificationLinkTest`, `HoldStatus.test.tsx` and
`NotificationBell.test.tsx`.

## API and security

| Operation | Endpoint | Access |
|---|---|---|
| Create | `POST /api/venue-bookings/{bookingId}/holds`, body `{ "expiresAt": "2027-03-01T18:00:00+08:00" }` | Venue Staff |
| History | `GET /api/venue-bookings/{bookingId}/holds` | Venue Staff |
| Release | `PATCH /api/venue-holds/{holdId}/release` | Venue Staff |
| Convert | `PATCH /api/venue-holds/{holdId}/convert` | Venue Staff |

Staff booking details and assignment-scoped coordinator booking responses include
hold history. Schedule responses include active holds. Hold DTOs contain server
time so the UI can schedule expiry without trusting the workstation clock.
Existing API conventions apply: 201 creation, 422 validation, 404 missing IDs,
403 role/assignment violations, 409 conflict/state changes, 503 write failures.

Venue Staff permissions remain role-based: individual venue ownership does not
exist in the current data model. New URL matchers precede broad authenticated
venue reads. Organiser arrangements do not expose internal hold details.

## UI

### Booking statuses and the optional hold step

| Stored booking status | Current meaning |
|---|---|
| `pending` (new-request default) | Awaiting Venue Staff's decision; blocking only if accompanied by an active, unexpired hold |
| `approved` | Venue committed to this booking; this does not confirm the event or bypass safety review |
| `changed` | Original booking superseded by an approved replacement; no longer blocks |
| `rejected` | Venue Staff declined the request; no longer blocks |
| `cancelled` | Request/booking withdrawn through cancellation; no longer blocks |

None of these stored statuses represents a tentative reservation. The shared
frontend `VenueBookingDisplayStatus` adds `tentative` as the effective display
state for `pending` plus an active hold. The separate persisted hold lifecycle
remains `active`/`expired`/`released`/`converted`; no additional PostgreSQL booking
enum or duplicate reservation is introduced.

Staff may choose **Approve Booking**, **Put on Tentative Hold**, or **Reject
Booking**. A hold is optional, not a prerequisite for every approval. Clicking
**Put on Tentative Hold** opens the expiry form. Once created, the ordinary
approval action is disabled and the hold exposes **Release Hold** and **Convert
Hold to Approved Booking**. Conversion approves the same booking atomically.

Booking Approvals now separates **Pending booking requests** and **Tentative
venue holds**, with the same counted filter component used by Organiser and
Coordinator lists. Active holds appear only in the hold section. Expiry/release
returns a still-pending request to the pending section; conversion removes it
from both decision queues.

Queue badges, detail status and calendar entries use shared status definitions
and theme colours: pending uses the pending theme, tentative is amber, and
approved is green. The calendar labels its committed entries **Approved booking**
to match `approved`, rather than implying event-level confirmation. Expired,
released, rejected, cancelled and superseded records are historical rather than
active calendar occupancy.

Successful hold actions immediately update local controls from the server
response and revalidate booking details. Open details, queues and calendars also
refresh in the background every five seconds and on focus; expiry timers remove
held occupancy at the server-derived deadline. Identical background timing data
does not replace unsaved setup/turnaround edits. Timing editing is disabled while
a hold is active.

Booking details retain the mounted page during same-booking expiry, focus and
manual refreshes. A refresh must not replace the content with a loading placeholder,
collapse the document height, reset scroll position or close the timing editor.
Navigation to a different booking retains its existing loading safeguards.

Hold creation/conversion conflicts use the same structured booking/unavailability
response, error surface and conflict cards as ordinary approval. Dates are
formatted in SGT; overlapping occupied periods are red even for buffer-only
conflicts. The creation endpoint returns all relevant conflicting bookings and
unavailable periods instead of embedding raw ISO timestamps in a message.

**Keep approval conflict checking and its UI.** Direct approvals can compete,
requests can predate a hold, and unavailability or other arrangements can change
before approval/conversion. A hold prevents overlapping reservations of the
**same venue**; different venues may be occupied concurrently. The event/venue
locks and final backend checks are still required on both approval paths.

Booking details provide create/release/convert actions and retained attempt history.
Ordinary approval is disabled while that booking has an active hold. Actions
refresh current booking data; backend failures retain entered expiry values.
The calendar distinguishes pending requests, amber tentative holds, approved
bookings, unavailable periods and existing approved-overlap indicators. A hold
appears once for its full occupied interval, with expiry and status in details;
its booking link opens staff actions. Coordinator booking views show hold history.

At a server-derived deadline the UI removes active hold occupancy immediately and
refreshes status. Expired entries cannot remain blocking merely because a refresh
fails. Existing focus refresh and manual calendar refresh remain available.

## Acceptance coverage

| Supplied requirement sections | Implementation / verification |
|---|---|
| 1–4, 14: creation, association, model, history | Child hold attempts on pending bookings; staff details panel; creation/validation/UI tests |
| 5–8: dates, occupied periods, conflicts, approval | Server clock, EffectivePeriod, shared blocking predicate; boundary/buffer/adjacency/approval tests |
| 9, 16: immediate and persisted expiry | Clock-driven effective DTOs, conditional worker transitions, deadline hook; exact-boundary/repeated-expiry tests |
| 10: release | Locked conditional release, retained history; repeated/expired/released-state tests |
| 11–12: conversion and concurrency | Existing approval transaction; changed-window guard; rollback, duplicate conversion and competing-writer tests |
| 13, 18: API, RBAC, errors | Dedicated controller, ordered security matchers and shared exceptions; negative role tests |
| 15: calendar | Distinct pending/hold types, one occupied interval, deadline refresh; calendar regression/component tests |
| 17: notifications and audit | Transactional expiry notification, existing activity types; notification failure/retry test |
| 19–20: testing and seeds | Real PostgreSQL concurrency fixtures, mutable server clock, UI tests; no production/static expiring seed rows |
| 21–23: integration and deliverables | Existing booking identity, locking, availability, safety workflow, notifications and audit reused; additive migration only |

Automated coverage lives in `VenueHoldFlowTest` and `VenueHoldPanel.test.tsx`,
with existing booking, availability, calendar, coordinator, activity and event
lifecycle suites used for regression. Test execution results are reported with
the implementation rather than inferred from this design document.

## Changed files

Paths below are relative to the relevant source root.

| Files | Change |
|---|---|
| Backend `venuebooking/hold/HoldClockConfig.java`, `VenueHold.java` | Injectable server clock, scheduler configuration and hold DTO including effective status/server time |
| Backend `venuebooking/hold/VenueHoldRepository.java` | Hold attempts, immutable occupied snapshots, conditional transitions, overdue selection and effective state |
| Backend `venuebooking/hold/VenueHoldService.java`, `VenueHoldExpiryJob.java`, `VenueHoldController.java` | Transactional create/release/expiry, retrying worker, history APIs and conversion through existing approval |
| Backend `venueavailability/AvailabilityRepository.java`, `AvailabilityService.java`, `AvailabilityModels.java` | One-query approved/held occupancy snapshot, hold-aware conflicts and schedule data |
| Backend `venuebooking/service/VenueBookingService.java` | Hold conversion in approval, self-exclusion, final deadline/window checks, rejection/replacement cleanup and timing protection |
| Backend `venuebooking/service/VenueBookingRequestService.java` | Cancellation releases holds; assignment-scoped booking responses include history |
| Backend `venuebooking/dto/VenueBookingDto.java`, `EventVenueBookingDto.java` | Staff/coordinator hold history in existing responses |
| Backend `notification/entity/NotificationType.java`, `notification/service/NotificationService.java` | Transaction-participating expiry notification and coordinator deep link |
| Backend `activity/entity/ActivityType.java`, `activity/service/ActivityService.java` | Hold lifecycle activities; automatic expiry visibly attributed to the system |
| Backend `config/SecurityConfig.java` | Explicit Venue Staff matchers before broad read matchers |
| Migration `V29__tentative_venue_holds.sql`, `SCHEMA.md` | Additive child table, constraints/indexes, notification type and schema documentation |
| Frontend `types/venueHold.ts`, `venueBooking.ts`, `venueBookingRequest.ts`, `notification.ts`, `activity.ts` | Shared API contracts |
| Frontend `features/venueStaff/VenueHoldPanel.tsx`, `HoldStatus.tsx`, `useHoldDeadline.ts` | Create/release/convert/history UI and clock-skew-resistant deadline handling |
| Frontend `features/venueStaff/BookingDetailsPage.tsx`, `BookingApproval.tsx` | Staff hold integration and ordinary approval disabled during an active hold |
| Frontend `features/venueStaff/VenueSchedulePage.tsx`, `VenueSchedulePage.css`, `venueCalendar.ts`, `VenueUnavailabilityPanel.tsx` | Distinguish real holds from pending requests; expose expiry; represent occupied intervals once |
| Frontend `features/coordinator/VenueBookingRequestPage.tsx`, `features/notifications/NotificationBell.tsx`, `components/ui/ActivityTimeline.tsx` | Coordinator hold visibility, expiry notifications and lifecycle labels |
| `VenueHoldFlowTest.java`, `ActivityServiceTest.java`, `VenueHoldPanel.test.tsx`, `VenueSchedulePage.test.tsx`, `e2e/tentative-holds.spec.ts` | New lifecycle/concurrency/failure/RBAC/browser coverage and updated pending-request wording |
| `docs/decision-log.md`, this document | Scope decisions, APIs, acceptance mapping and verification |

## Verification — 18 October 2026

- Backend Maven `verify` with the 13 affected hold/booking/availability/lifecycle/
  audit/security test classes: **238 passed**, zero failures/errors/skips. This
  includes 17 real-PostgreSQL hold tests. Packaged Spring Boot jar successfully.
- Frontend `npm test`: **48 files, 471 tests passed**.
- Frontend `npm run lint` and `npm run build`: passed.
- Playwright `golden-path.spec.ts` and `tentative-holds.spec.ts`: **2 passed**
  against the final packaged backend and built frontend. The new journey checks
  hold creation, calendar occupancy, ordinary approval disabled while held,
  release, retained attempts, re-holding, conversion without duplicate bookings,
  activity history and the event remaining in Planning. The existing journey
  confirms the safety-review and event-confirmation workflow still works.
- `git diff --check`: passed.

Database verification used the isolated local `csphere_vsc3_test` database;
production data and the running development database were not modified. V29
applies when the updated backend starts against a target database. Temporary
browser verification servers used ports 18083 and 14175 and were stopped after
verification. No commit or push was performed.

The initial sandbox attempts could not reach PostgreSQL/share Vitest temporary
files or attach Mockito; those environment restrictions were resolved by running
the affected checks outside the sandbox. Playwright's missing browser was
installed into the ignored workspace dependency cache. Final checks above ran
successfully. Maven's coverage report also reported stale local JaCoCo execution
data from earlier builds; no coverage percentage is claimed.

### Booking approvals UI refinement — 18 October 2026

Staff can approve a pending request directly, put it on tentative hold, or reject
it. Holds are optional and have a separate counted queue filter. The persisted
booking statuses remain unchanged; a pending booking with an active hold displays
as Tentative Hold. Approval and conversion both retain final conflict checks.

- Backend hold and approval regression suites: **52 passed** (18 hold tests and
  34 approval tests), zero failures/errors/skips; Maven verify/package succeeded.
- Full frontend suite: **48 files, 476 tests passed**; lint and build passed.
- Browser journeys: **2 passed**, including queue separation, hold conflict cards,
  matching calendar/status colours, and create/release/convert without reloading.
- Desktop and mobile screenshots checked for date formatting, button/form spacing,
  red conflicting periods, and horizontal overflow.
- The development Docker backend was rebuilt. Browser verification used the
  isolated test database and temporary ports 18083/14175. No commit or push.

## Hold history presentation (25 October 2026)
Each saved hold now uses the shared venue preview Card surface, responsive padding,
status badge and information-field styles. Labels use the same weight and values
use the same muted colour and font size as the venue/event preview. Occupied,
expiry and creation times remain Singapore time; fields wrap into a single column
on narrow screens. Hold creation/release/conversion and deadline rules are unchanged.

Focused verification: 63 hold/booking component tests and two desktop/mobile
browser journeys passed. Browser assertions compare actual card padding, radius,
background, label weight and value colour with the existing previews, then check
hold expiry and focus-refresh scroll behaviour. Build and lint passed.

The hold-history status badge is aligned at the card's top right, on the same row
as the occupied/expiry/creation facts, rather than above them. The fact grid wraps
on narrow screens. Desktop/mobile browser checks verify the badge and facts share
their top edge and do not overlap; both expiry/scroll journeys still pass.
