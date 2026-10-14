# Venue booking API and review workflows

## Current timing and multi-venue contract (14 October 2026)

Each booking has its own start/end window and saved setup/turnaround durations. Occupied time is `[booking start − setup, booking end + turnaround)`. Legacy nullable booking timestamps fall back to event timestamps. Advertised event requirements remain available separately in the staff response's `event` object.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/events/{eventId}/venue-options` | Assigned coordinator; optional `startDatetime`, `endDatetime`, `setupDurationMinutes`, `turnaroundDurationMinutes` recheck the proposed occupied window |
| POST | `/api/events/{eventId}/venue-bookings` | Assigned coordinator; creates one pending booking, returns 201 |
| POST | `/api/events/{eventId}/venue-bookings/batch` | Assigned coordinator; accepts `{ "bookings": [...] }`, returns 201 and an array; all bookings succeed or the transaction rolls back |
| GET | `/api/events/{eventId}/venue-bookings` | Assigned coordinator or Safety Officer under existing event-access rules; includes booking-specific times and `timing` |
| PUT | `/api/venue-bookings/{bookingId}/timing` | Venue Staff; updates saved setup/turnaround durations with a reason and stale-write guard |
| GET | `/api/venues/{venueId}/timing-conflicts` | Venue Staff; lists overlaps between approved occupied periods |
| POST | `/api/venues/{venueId}/timing-conflicts/scan` | Venue Staff; detects overlaps and sends deduplicated coordinator notifications |

New requests are allowed in Planning (`pending` or `safety_changes_requested`), with the existing confirmed-event replacement exception. Multiple venues are supported; another active booking for the same venue on the same event remains disallowed. Existing replacement restrictions are unchanged.

See [timing request/response examples, editing rules and migration policy](venue-booking-duration-snapshots.md). The sections below retain the individual slices' historical implementation and verification notes; the current contract above and VS08 conflict fields below describe the integrated behavior.

## VS02 / VS16 — Pending booking review and booking/event reads

VS16 exposes existing booking relationships and current event requirements through
Venue Staff catalogue navigation and a shared read-only booking-detail page. VS02
adds the Venue Staff pending queue and queue-to-detail navigation, with a VS-only
queue endpoint. Neither story implements booking decisions or event updates.
Existing shared read access rules remain unchanged.

## Endpoints

### VS02 pending queue

`GET /api/venue-staff/booking-requests` returns an array of the existing booking
detail response, restricted to booking status `pending`. Results are ordered by
current event start time, then booking UUID. Venue and event relationships are
fetched together. An empty queue returns 200 with `[]`.

This endpoint requires the authenticated `VS` role (401 when unauthenticated,
403 for other roles). This is role enforcement only: staff-to-venue management
relationships do not exist yet, so the queue currently includes pending bookings
across venues. Per-staff managed-venue acceptance remains deferred.

The operation uses the existing read-only service and DTO mapping, reads current
event/venue records, and makes no updates. Existing detail and per-venue list
endpoints retain their access rules and all-status behaviour. No frontend or
Coordinator booking-submission workflow is introduced by this backend slice.

`VenueBookingReadTest` covers pending filtering, linked records, latest values and
status changes, empty results, role/authentication rejection, unchanged shared
reads, and before/after record snapshots. Fixtures roll back after each test.

Verified 2026-09-25: all 15 `VenueBookingReadTest` cases passed against local
PostgreSQL with Java 25. The existing dev-profile startup seeder also created five
missing dev accounts during this run; that startup behaviour is separate from
the transactional booking fixtures and read-only queue operation.

## VS02 pending list UI

`/venue-staff/booking-approvals` now uses the pending queue endpoint inside the
existing Venue Staff route gate. Cards show event name, venue address, explicit
`Status: Pending`, both dates/times in Singapore time, expected attendance and a
venue-requirements summary limited to 200 characters. Blank requirements show
`Not specified`; full requirements remain on the existing booking detail page.
Page-specific styles wrap long text. The UI also excludes any non-pending response
records before rendering.

Initial loading, refresh-in-progress, empty results and retrieval errors are
separate states. Refresh fetches the latest queue; errors offer Try again.
Each card links to `/venue-staff/bookings/:bookingId?from=booking-approvals`.
The query parameter carries navigation context across refreshes and direct links;
it is not an authorization mechanism. In this context the existing detail page
shows `Back to Pending Booking Requests` and hides catalogue Previous/Next controls
and their all-status booking-list fetch. Without this parameter, catalogue return
navigation and pagination retain their existing behaviour.

Details perform a fresh GET for the selected booking. Queue-origin navigation does
not retain a previous booking's panels while another request loads or fails. A
current `approved`, `changed`, `rejected` or `cancelled` status displays a clear
no-longer-pending notice alongside the saved status and details. The queue performs
a fresh read when returning through the explicit link or browser Back. There is no
polling: a status change after details have loaded appears on refresh/reopening.
Missing/malformed IDs display the API's normal error and keep the return link and
retry action available. Late responses after leaving the page are ignored.

All review operations remain GET-only. Current `events` values, not pending
`event_requests` amendments, remain authoritative. Existing venue editing is a
separate link/workflow; viewing a booking performs no writes.

Final VS02 checks (2026-09-25): 62 tests passed across all Venue Staff test files and
App routing; scoped ESLint and production build passed. New cases cover all four
non-pending status transitions between queue/detail reads, fresh attendance and
requirements, returning to a refreshed queue, direct-link/reopen context, 400/404
errors with retry, no queue-origin pagination fetch, GET-only access, and ignoring
late responses. Existing catalogue navigation/pagination tests remain passing.
The 15 PostgreSQL booking-read integration cases passed earlier in this slice;
backend code was unchanged for the final navigation work and was not rerun.

Browser checks against existing demo data confirmed all three pending entries,
Workshop and Seminar detail selection, hidden queue-origin pagination, preserved
context after refresh, explicit return and browser Back (including queue loading).
The queue was visually inspected at the available narrow browser viewport. Mixed
statuses and failures were checked with isolated automated fixtures, without
changing the demo bookings. Per-staff managed-venue ownership remains deferred;
the new queue enforces VS role access, while shared detail endpoints retain their
existing authentication rules.

## Automated test inventory

Counts include parameterized cases. These are the last verified results from
2026-09-25, not a claim that tests run automatically when documentation is edited.

| Test file | Cases | Purpose |
|---|---:|---|
| [BookingApprovalsPage.test.tsx](../frontend/src/features/venueStaff/BookingApprovalsPage.test.tsx) | 14 | Pending list/status and summary fields; loading versus empty; errors/retry; refresh; optional/long requirements; frontend role gate; queue-to-detail-to-queue flow; all four non-pending status changes; current detail values; direct-link/reopen context; 400/404 errors; ignored late responses |
| [BookingDetailsPage.test.tsx](../frontend/src/features/venueStaff/BookingDetailsPage.test.tsx) | 9 | Catalogue-to-detail regression coverage, current data, optional requirements, empty/error states, pagination, retained panels and keyboard focus |
| [VenueBookingReadTest.java](../backend/src/test/java/com/example/connect_sphere/venuebooking/VenueBookingReadTest.java) | 15 | Real PostgreSQL filtering and associations, latest saved event data, unapplied amendment isolation, record snapshots proving reads do not write, empty results, 400/404 responses, VS queue access, 401/403 rejection and preserved shared-read access |

The “pending-list test” refers to `BookingApprovalsPage.test.tsx`: it runs the UI
against simulated HTTP responses, including failures and status changes. It does
not connect to or change real bookings. Frontend checks use Vitest and React
Testing Library; they are not automated browser end-to-end tests. Backend tests
use transactional database fixtures that roll back; dev-profile startup seeding
is separate and may create missing development accounts.

The 14 pending-list cases are included in the 62 passing Venue Staff/routing
frontend cases, not additional to them. The 15 backend cases are a separate run.
Per-staff venue ownership is not covered because that relationship is not
implemented. Actual browser Back, full-page refresh persistence and visual layout
have manual checks; automated tests cover component reopening, direct links and
explicit return navigation. Responsive layout across all viewport sizes is not
claimed. See [test commands and manual checks](vs16-manual-testing.md#vs02-automated-tests).

## Existing VS16 endpoints

| Method | Path | Result |
|---|---|---|
| GET | `/api/venues/{venueId}/bookings` | Array of associated booking details, ordered by current event start time then booking UUID |
| GET | `/api/venue-bookings/{bookingId}` | One booking detail |

Both return 200 on success. An existing venue with no bookings returns `[]`.
Unknown venue/booking UUIDs return 404 with the existing `ApiError` shape
(`message`, `missingFields`). Malformed UUIDs return 400. The list includes all
booking statuses; it is not a pending-approval queue or an availability calculation.
Filtering by venue is a relationship filter, not an authorization boundary.

## Response

Each detail has `bookingId` (UUID), `status` (existing lowercase database label),
`venue` (the existing `VenueDto`), and `event`:

- `eventId`, `eventName`
- `startDatetime`, `endDatetime`: offset timestamps representing the saved instants;
  clients should render them in the agreed display timezone, including both dates
  for overnight events.
- `expectedAttendance`
- `venueRequirements`: existing free text, not a new structured selection list.
- `accessibilityNeeds`: existing stored accessibility labels.
- `equipmentRequirements`: existing optional event field, exposed verbatim without
  inferring which equipment is venue-provided.

Missing optional equipment information remains null/blank and empty accessibility
arrays remain empty. A future UI should display `Not specified` for missing optional
values; an explicit `none` accessibility label has a distinct meaning. No invented
`otherVenueRequirements` or venue-name field is added. Venue address remains the
existing venue identifier shown to users.

## Current data and read-only behavior

`VenueBooking.event` maps `venue_bookings.event_id` to `events.event_id`.
`VenueEvent` is a minimal immutable read mapping of the current `events` row, not
`event_requests`. Pending creation/amendment requests never supersede event values.
A separate accepted-change workflow must update the event; the next GET then reads
those saved values. There is no frontend snapshot, history mechanism, or event-copy
operation here.

The service uses read-only transactions. Event and booking mappings are immutable;
the booking repository exposes only reads, relationships have no cascading writes,
and no mutation endpoint is introduced. Existing venue mapping/DTO conversion is reused.

## Authorization limitation

As explicitly requested, this slice does not implement or alter authorization.
The application's shared security configuration now requires authentication for these
reads. VS16 does not add staff-to-venue ownership checks or change those rules.
The Venue Staff frontend gate alone does not establish venue-management scope.
Full VS16 authorized-scope acceptance remains deferred. Read-only integration tests
use an authenticated VS test principal; they do not claim ownership enforcement.

## Future reuse

The shared `/venue-staff/bookings/:bookingId` page uses the detail endpoint and is ready for navigation from
Catalogue → venue details → associated booking now, and VS02 Booking Approvals later. Approval/rejection
actions and queue behavior remain separate work; no decision logic is embedded in
these reads. No bookings are generated merely to populate a list.

## Verification

`VenueBookingReadTest` uses real PostgreSQL with transactional fixtures:

- All five existing booking statuses and linked venue/event values.
- A pending amendment with attendance 999 does not replace current event attendance 120.
- Updating the event fixture makes a subsequent GET return new dates, attendance,
  venue requirements, accessibility and equipment requirements.
- Listing excludes another venue's bookings, has deterministic ordering, and returns
  an empty array for an existing unbooked venue.
- Unknown IDs return 404; malformed UUIDs return 400.
- Complete ordered snapshots of `events`, `venues`, `venue_bookings` and
  `event_requests` remain identical before/after detail and list GET requests.

Test fixtures roll back. These are API/database integration tests, not browser tests.

Verified 2026-09-22: full backend suite passed — **138 tests, zero failures/errors**,
including 8 VS16 integration cases. Run with Java 25 and local PostgreSQL:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/csphere'
.\backend\mvnw.cmd -f backend/pom.xml -B -ntp test
```

## Venue Staff screens

- `/venue-staff/catalogue`: existing cards now link to venue details.
- `/venue-staff/catalogue/:venueId`: shared venue panel and associated bookings,
  including loading/error/retry and empty states.
- `/venue-staff/bookings/:bookingId`: shared `BookingDetailsPage` with
  `VenueDetailsPanel` on the left and read-only `EventRequirementsPanel` on the right.
  Narrow/short viewports stack panels to keep information accessible.

Each mounted detail page performs a new GET, including reopening after navigating
away. Leaving a page ignores its outstanding response. There is no automatic polling
or copying of pending event requests. Dates include both start/end calendar dates and
explicit Singapore timezone labels. Missing optional values show `Not specified`;
explicit accessibility `none` shows `No accessibility requirements`.

All routes sit inside the existing Venue Staff gate. No new top-level section or
approval control was added. VS02 should link to this exact booking route rather than
creating a second detail page. Editing venue information remains a separate VS07 link.

`BookingDetailsPage.test.tsx` covers the full catalogue-to-booking navigation,
refetching updated attendance after reopening, GET-only access, overnight date
rendering, optional placeholders, explicit accessibility none, empty booking lists,
and error/retry behavior. HTTP responses are mocked; these are not live-browser tests.

Frontend verification (2026-09-22): 30 tests passed across VenuePages.test.tsx and BookingDetailsPage.test.tsx; production build and ESLint passed.

## Local seed for component developers

Venue Staff and Event Coordinator developers can use the self-contained demo venue,
event and pending booking without implementing Coordinator workflows first.
See [VS16 manual testing and cleanup](vs16-manual-testing.md). Scripts live under
`backend/dev/seed/`, require explicit invocation and are not Flyway migrations.
Seeded bookings use the same detail route as future workflow-created bookings.

## Booking pagination

The heading stays above the two-column layout. Previous/Next controls on the right
show `Booking N of M` using the selected venue's associated bookings in API order.
Each control uses client-side navigation to update `/venue-staff/bookings/:bookingId` and fetches fresh detail data;
list data is used for navigation, not as a cached event-detail response. Ends are
disabled, and navigation failures can be retried without hiding current details.

Pagination replaces the independently scrolling requirements container and its
height-measurement hook. There is no nested scrollbar or clipped content. Long
requirements use ordinary document flow. Existing desktop columns and stacked
small-screen layout remain. VS02 can reuse this page unchanged.

Navigation, values, reopen/refetch, 404 handling, optional placeholders and GET-only
viewing are covered in `BookingDetailsPage.test.tsx`. Added missing-venue and booking
list failure/retry cases. Eight real-PostgreSQL `VenueBookingReadTest` cases passed
again after adapting the test principal to the repository's existing authentication.
These tests compare complete records across events, venues, bookings and requests
before/after reading. No production authorization or other-role workflow was changed.

VS02 must reuse `/venue-staff/bookings/:bookingId`, `VenueDetailsPanel` and
`EventRequirementsPanel`; add any future approval actions as a separate component,
not a duplicate detail screen. VS16 exposes no approval/rejection action.

Latest pagination checks (2026-09-22): 35 Venue Staff UI tests, scoped ESLint, and production build passed. The earlier 8 PostgreSQL VS16 read tests passed; backend tests were not rerun for these frontend-only fixes. Full frontend lint reports two existing react-hooks/set-state-in-effect errors in technicalSupport/status/equipmentStatusPage.tsx (lines 56 and 71); that other-role file was not modified.

Pagination keeps the previous detail panels and navigation mounted while the next
GET is pending, with a loading indicator and temporarily disabled controls. This
avoids collapsing the document and losing scroll position during loading. The venue
booking list is retained across same-venue pagination rather than refetched each time.
Failed detail requests and retries also retain the previous panels and pagination.
An error explicitly identifies the content as the previously loaded booking; a
successful retry replaces it with the requested booking. Initial failures still
show an error without inventing details.

Previous/Next remain the same button elements throughout loading and at boundaries.
They use `aria-disabled` with guarded activation so keyboard focus is retained and
unavailable controls cannot navigate. They never submit a form or reload the page.
Regression tests verify panel identity through loading, failure and retry, keyboard
focus retention, blocked repeated activation, and one list fetch while paging.
These DOM tests do not measure browser scroll position or layout geometry.

## VS03 — Approve booking (2026-10-05)

Venue Staff review the existing `/venue-staff/bookings/:bookingId` page and select
**Approve Booking**, then confirm the event, venue and requested start/end times.
Cancelling confirmation makes no API request. Approval is available only for a
successfully loaded pending booking, from either the queue or the catalogue.

`PATCH /api/venue-bookings/{bookingId}/approve` returns the updated booking DTO.
SecurityConfig requires the VS role. Venue-to-staff ownership is still not
modelled, so any Venue Staff account can approve any pending booking.

V16 renames the PostgreSQL venue booking enum value `confirmed` to `approved` in
place, preserving existing rows. Event confirmation uses a separate enum and is
unchanged. All booking availability, active-request and operational-issue queries
now use `approved`. Deploy the application and migration together: older backend
versions expect the former enum label. Rebuild the backend image for Flyway to
load V16; do not edit previously applied migrations.

Approval takes the existing event lock (shared with request submission and
cancellation), then a venue lock to serialise approvals of competing events.
Inside that transaction it checks pending status and overlapping approved
bookings, using half-open time windows so back-to-back bookings are allowed.
A conditional update changes only status on the existing row; a fresh DTO is
read after clearing cached entity views. Failure rolls back the transaction.
This protects approval through the service; future schedule-changing workflows
and direct SQL must maintain the same consistency rules.

Responses: 400 malformed UUID, 401 unauthenticated, 403 wrong role, 404 missing
booking, 409 non-pending/duplicate/overlap, 503 database or transaction failure.
The UI uses the successful response as its status source, blocks duplicate clicks,
and displays errors without optimistically marking approval. On an uncertain
network result, refresh details before retrying. Returning to the pending queue
fetches it again, excluding the approved booking.

Tests: `VenueBookingApprovalTest` covers status-only updates, role restrictions,
non-pending and duplicate approvals, missing IDs, overlap boundaries, real database
failure rollback and simultaneous duplicate/competing approvals. Booking details
and queue UI tests cover confirmation, cancellation, loading, failures and queue removal.
Rejection actions remain outside VS03.

### VS03 failure recovery and local smoke verification

The approval action shares the **Venue information** header row: heading left,
button right. Confirmation appears below that row. Successful approval uses the
returned booking and removes the action; returning to the queue fetches fresh data.

After any failed PATCH, the page fetches the booking again. This reconciles stale
status, concurrent cancellation/approval, and a lost response after a committed
update. A missing booking clears the stale details and action. If both the write
and verification read fail, approval stays disabled until **Refresh booking details**
succeeds. The page never infers success from an error or retries the write blindly.

**Approval PATCH returns a generic 404 while GET details works:** check the running
backend version. Vite forwards `/api` to port 8080; it does not implement the route.
An old backend image can serve existing details but lack the new PATCH action.
Run `docker compose up -d --build backend`, wait for backend health, and confirm
Flyway V16 is applied. A domain 404 with a booking-not-found message instead means
that the requested booking does not exist. Do not change the endpoint or create a
replacement booking to work around an outdated container.

Live smoke check (2026-10-05): through `localhost:5173` and the rebuilt Spring Boot
backend, using two temporary bookings on a separate test venue:

1. Open a pending request and verify the header/action placement.
2. Open confirmation and cancel; verify its database status is still pending.
3. Confirm approval; verify approved status and absence of the approval action.
4. Return to the queue; verify the approved request is absent.
5. Attempt an overlapping request; verify conflict feedback and pending status.
6. Verify duplicate approval returns 409 and booking associations/notes are preserved.

The originally reported demo booking is left pending so the developer can exercise
its approval manually. Temporary smoke records are removed after verification.
Automated coverage additionally exercises missing bookings, stale approved/cancelled
states, network recovery, database rollback, role access and simultaneous approvals.

Validation (completed 2026-10-06): 47 booking backend tests passed in an isolated
container build; all 192 frontend tests passed, with the 38 booking UI tests rerun
after the final recovery-message adjustment. Frontend production build and
changed-file lint passed. Repository-wide lint still reports five existing
`set-state-in-effect` errors in the technical-support equipment reservation/status
pages. Live browser approval, cancellation, queue removal and conflict checks
passed on 2026-10-05; temporary smoke records were removed afterward.

The booking status pill appears beside **Event Requirements** on the shared
booking details page, for both catalogue and pending-queue navigation.

Booking status colours are consistent across that pill, the venue's associated
booking list, and coordinator booking history:

| Status | Appearance |
|---|---|
| `approved` | Green (`--success`); pill uses `--success-soft` background |
| `rejected` | Red (`--danger`); pill uses `--danger-soft` background |
| `pending`, `changed`, `cancelled` | Existing appearance retained |

The shared `data-booking-status` styling applies only to booking labels, leaving
event/request status styling unchanged. Existing theme tokens support light and
dark mode, and text labels remain visible so status is not conveyed by colour alone.

Status-colour regression coverage lives in `bookingStatusColours.test.ts`,
`BookingDetailsPage.test.tsx`, and `VenueBookingRequestPage.test.tsx`. These cover
the stylesheet's green/red tokens and unchanged default pill colours, plus all
five status labels on booking details, associated bookings, and coordinator history.
The stylesheet test resolves light-theme custom properties before checking the
CSS cascade because JSDOM does not resolve them; it is not a visual browser test.

## VS04 rejection and VS09 alternatives

`PATCH /api/venue-bookings/{bookingId}/reject` requires Venue Staff access and
JSON `{ "reason": "Venue unavailable due to maintenance.", "alternativeVenueId": "…", "alternativeArrangement": "Move the event to the afternoon." }`.
A nonblank reason is required and saved after trimming. `alternativeVenueId`
and `alternativeArrangement` are optional; the venue must exist and differ from
the requested venue, and arrangement text is limited to 2,000 characters.
Either suggestion may be provided independently or both together. Returns the
updated booking including `rejectReason`, `alternativeVenueId`,
`alternativeVenueAddress` and `alternativeArrangement`, also available in
coordinator booking history.

The event lock shared with approval/submission/cancellation and a pending-only
conditional update ensure status, reason and any suggestions commit together.
Non-pending decisions return 409 without overwriting the reason; missing
bookings return 404, invalid reasons/suggestions 422, and database update
failures 503.

The booking details page provides an optional alternative venue selector and
free-text arrangement field along with the rejection reason. Both decision
actions disappear after rejection, and the saved reason and suggestions are
displayed to Venue Staff and the Event Coordinator. The pending queue excludes
rejected records. After an uncertain response the UI reads current booking data
before allowing another decision. Rejection does not create a second booking or
modify the original event requirements.

## VS08 approval conflicts

Approval now returns HTTP 409 with `message` and a `conflicts` array for direct
overlaps and buffer-only conflicts with approved bookings. Each conflict contains `bookingId`,
`eventName`, booking `startDatetime`/`endDatetime`, saved `setupDurationMinutes`/`turnaroundDurationMinutes`, and `effectiveStart`/`effectiveEnd`. Timestamps are offset date/time
values; clients should display them using the existing Singapore-time formatter.

The query runs after the existing venue lock and before any booking updates. It
uses stored booking times (event-time fallback for legacy nulls), the same venue, booking status `approved`, and
`existingStart < requestedEnd AND existingEnd > requestedStart`. Only the current
booking ID is excluded; an approved booking still counts when its associated
event is completed or cancelled. Results are ordered by start time and booking ID.
A blocked request remains pending and existing approved bookings are unchanged.

Existing unavailability and setup/turnaround checks remain in place, so adjacent
event times can still be blocked by nonzero buffers. Buffer-only conflicts explain
that setup or turnaround time caused the block and identify the blocking bookings.
Recorded unavailable periods are returned in a separate `unavailablePeriods` array
with `unavailabilityId`, `reason`, `startDatetime`, and `endDatetime`. Approval
collects all booking conflicts and unavailable periods before returning 409, using
the same venue lock. Booking conflicts are deduplicated by booking ID. Empty
arrays are returned when only the other type blocks approval. Period matching
uses the request's existing setup/turnaround-expanded range and half-open bounds.
Other booking-state errors retain the message-only format. Database and transaction failures still return 503.

The existing booking approval page displays every returned conflicting event name,
booking ID, and start/end date and time in Singapore time, using the shared Card
and Button components. Each card has a full-width Booking Name row, a two-column
Starts/Ends row, and a two-column Setup Period/Turnaround Period row. The third row displays the saved durations and corresponding setup/turnaround ranges, including explicit zero-minute values. A separate occupied-period row shows the full interval.

For partial direct overlaps, the request end and conflicting start (or request
start and conflicting end) are bold red and labelled "overlap". Containment and
identical periods highlight both boundaries of both bookings. The request's
highlights appear in Event Requirements and combine all returned conflicts.
Adjacent or buffer-only event periods do not receive direct-overlap highlights.
Unavailable periods use the same card layout and highlighting, with a full-width
Unavailability Reason row and a Starts/Ends row. They have no setup/turnaround
placeholder row. Both types contribute to the current request's highlighted
boundaries and remain visible if the automatic status refresh fails.
Changing bookings clears conflict state. The API client retains
typed conflict details and ignores malformed entries so error rendering remains
usable. After a failed approval, the page still fetches the latest booking with
`cache: no-store`; conflict details remain visible through that automatic refresh,
including when it fails. Failed refreshes disable decisions until a successful
refresh. A manual refresh or new decision clears the previous conflict details.
No approval success is inferred from a conflict response.

After a confirmed approval attempt is blocked by recorded unavailability, the
page shows one combined explanation with the Singapore timezone and disables
Approve Booking for the remainder of that page visit. A successful explicit booking-detail refresh permits a new server-checked approval attempt. Saving timing edits clears stale approval conflicts.
Reject Booking remains available after a successful status refresh. The pending
status remains visible on the booking rather than being repeated in this error.

## VS01 integration (6 October 2026)

Venue options, requests and approval now use buffered occupancy and recorded unavailability. Booking DTOs include `requiresAlternative` and `replacesBookingId`. A coordinator may request one replacement for an affected active booking; the original becomes `changed` only when the replacement is approved. See [Venue unavailability API and workflow](venue-unavailability-api.md) for the current rules, endpoints and verification steps. Earlier descriptions above that limit all requests to Planning or describe raw event-time overlap are superseded by these VS01 rules.
