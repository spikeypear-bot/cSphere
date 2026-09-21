# VS16 — Venue booking/event reads

VS16 exposes existing booking relationships and current event requirements through
Venue Staff catalogue navigation and a shared read-only booking-detail page. No
booking decisions, event updates, event-request workflows, or authorization changes
are included.

## Endpoints

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
The endpoints currently have no backend identity or staff-to-venue permission
checks. The existing Venue Staff frontend gate does not protect direct API access.
VS16's authorized-access acceptance criteria remain unmet/deferred and must not be
claimed complete. No access-denial test is claimed for a control that does not exist.

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
