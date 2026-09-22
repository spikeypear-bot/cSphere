# Venue catalogue API

VS06A/VS06B catalogue creation and reads, plus VS07 updates to existing venues.
No booking or approval workflow.

| Method | Path | Success |
|---|---|---|
| POST | `/api/venues` | 201, saved venue DTO and `Location: /api/venues/{id}` |
| GET | `/api/venues` | 200, array ordered by address then UUID; `[]` when empty |
| GET | `/api/venues/{id}` | 200, single venue DTO |
| PUT | `/api/venues/{venueId}` | 200, updated venue DTO |

POST fields: `venueAddress`, `venueCapacity`, `supportedLayouts`,
`operatingInformation`, optional `additionalInformation`. Capacity is one shared
integer from 1 through 50,000. The frontend default of 50 is not supplied by this
API. Layout labels: `classroom`, `theatre`, `boardroom`, `banquet`, `exhibition`.
The response adds `venueId` to these fields.

Optional `venueAccessibilities` and `venueFacilities` arrays record venue provisions.
On POST, omitted or null arrays become empty arrays; responses always include both arrays.
PUT uses the distinct omission/null semantics below.
Empty means no selections recorded. Accessibility `none` explicitly records no
provisions and cannot be combined with other accessibility selections. Null entries
and duplicate selections return 422; unknown enum labels or malformed types return 400.
Accessibility labels match `AccessibilityFeature`; facility labels are
`audio_visual_equipment`, `air_conditioning`, `breakout_spaces`, `projection`,
`stage`, `dining_area`, and `barbeque_pit`.

The entity stores enum labels as string collections and casts write parameters to
PostgreSQL `accessibilities[]` / `facilities[]`. Existing column types and GIN
indexes are unchanged. This avoids the named-enum-array binding issue documented
in V4 without changing the Venue schema. No filtering is added.

Errors use the shared `ApiError` shape: `message` and optional `missingFields`.
Malformed JSON/types/layout labels or malformed UUIDs return 400; service
validation returns 422; an unknown venue UUID returns 404.

## VS18 venue identity and implementation review

Each existing `venues` row represents one independently bookable room or space,
with its own `venueId`. `venueAddress` contains the room identity and location and
is the user-facing identifier, for example `School A - Classroom 1, Level 2`.
Classroom 2 and Theatre 1 at the same school are separate records with separate
IDs. No parent building record, dedicated name field, or schema change is needed.
`supportedLayouts` describes arrangements supported by that room, not child rooms.

The existing catalogue fetches `GET /api/venues` and renders one panel per
`venueId`, headed by `venueAddress`. Detail and edit links retain the UUID.
The detail page fetches `GET /api/venues/{id}` and separately reads associated
bookings. Both venue service reads use read-only transactions; they map saved
rows directly without grouping by building or expanding layouts into rooms.
The existing detail panel displays all recorded characteristics.

## Current access rules (reviewed 2026-09-23)

`SecurityConfig` requires authentication for venue GET endpoints, including
individual records. Reads are available to any authenticated role. Venue writes
require EC or VS. The Venue Staff frontend routes use the session role gate
`useRoleGate('venue-staff')`; server authentication uses validated bearer tokens.

**Confirmed VS18 scope (2026-09-23): all Venue Staff can view all venues for now.**
The authorised scope is the entire catalogue for every Venue Staff account.
Both list and individual-record endpoints require server-validated authentication.
Existing read access for other authenticated roles is preserved, as specified in
D20. The list returns the whole catalogue without pagination.

**Deferred:** staff-specific venue restrictions will be introduced later. There
is currently no staff-to-venue assignment or ownership filter. That future work
must define the assignment rule and enforce the same scope on list queries and
direct record lookups (including related booking reads). It must not rely solely
on hiding links in the frontend. No assignment field or schema change is added
for the current VS18 scope.

## VS18 current characteristics and verification

The catalogue and individual detail view use fresh GETs on opening/reopening,
with browser cache bypassed (`cache: 'no-store'`). Refresh controls fetch current
saved values while the page is open. This is refresh-on-demand, not live polling.
Old venue data is hidden during refresh and after a failed or denied request.
Associated bookings are requested only after the venue read succeeds.

The detail panel shows address, ID, capacity, supported layouts, accessibility,
facilities, operating information and optional additional information. Empty
accessibility/facility arrays show `Not recorded`; explicit accessibility `none`
shows `No accessibility provisions`. Blank additional information has a clear
fallback. The catalogue provides loading, retry/error and empty states.

Verification coverage:

- `VenueRead.test.tsx`: separate rooms at the same location, summaries and UUID
  links, full details and empty selections, latest values on refresh/reopening,
  denied/missing records, removal of stale records after denial, empty catalogue,
  and GET-only requests with cache bypass.
- `VenueControllerTest`: authentication required for list and direct reads;
  invalid bearer tokens rejected; two Venue Staff accounts can read the same
  records; saved updates returned on subsequent reads; complete venue, event and
  booking row snapshots unchanged by list/detail/associated-booking reads.
- No separate availability model exists. The snapshot check protects all venue
  columns and booking statuses that currently represent the relevant state.

Tests use mocked HTTP in React and real PostgreSQL with transactional rollback
in backend integration tests; they are not a live-browser end-to-end test.

Executed 2026-09-23: 41 frontend venue tests and 74 `VenueControllerTest`
integration tests passed; frontend production build passed. Full-project lint
reports two existing `react-hooks/set-state-in-effect` errors in
`features/technicalSupport/status/equipmentStatusPage.tsx` (lines 56 and 71).
Use Java 25 for backend verification; the local `JAVA_HOME` default points to
Java 17 and must be overridden for the Maven command below.

## VS07 characteristic updates

PUT accepts only characteristic changes: `venueCapacity`, `supportedLayouts`,
`venueAccessibilities`, `venueFacilities`, `operatingInformation`, and optional
`additionalInformation`.
Omitted fields retain their saved values. Explicit null is rejected (400).
Empty optional accessibility/facility arrays clear selections; empty layouts are
invalid (422). Existing creation validation applies before any mutation.
An empty object leaves values unchanged. Venue ID and address are preserved. Omitted additional information is preserved;
a supplied string is trimmed, and an empty string clears it. Unknown venues return 404. The operation neither
reads nor changes bookings. Existing API authorization limitations above still apply.

Update validation errors identify the affected API field in `message`, including
JSON-level errors (explicit null, unknown selections, and fractional capacity).
Malformed JSON without an identifiable field returns a general invalid-JSON error.
Capacity must be a whole number from 1 to 50,000; layouts must be nonempty;
operating information must be nonblank. Selection arrays reject null entries and
duplicates, and accessibility `none` cannot accompany another selection.

## VS07 verification — 2026-09-22

Scope includes the five original characteristics and the subsequently requested
editable Additional information field. Address and venue identity remain unchanged.
No production booking code, other-role workflows, schema, or authorization framework
was changed for this story.

| Acceptance criterion | Evidence |
|---|---|
| AC1: permitted Venue Staff | Existing UI role gate inspected in `App.tsx`; backend identity/management authorization remains unresolved, as above |
| AC2: current values before editing | `VenuePages.test.tsx` loads capacity, layouts, accessibility, facilities, operating information, and additional information |
| AC3–4: individual/combined updates and preservation | `VenueServiceTest.updatesOnlySubmittedFields` and `VenueControllerTest.updatesOnlySubmittedFieldsAndPersists`; UI tests assert only changed fields are submitted |
| AC5–6: validation and actionable errors | `validationNamesFieldAndPreservesSavedValues` checks field names and database rereads after rejection; service test verifies validation precedes mutation; UI test verifies input survives errors |
| AC7: success confirmation | Individual and combined UI update tests assert the success message and refreshed catalogue |
| AC8: latest values on reopening | Controller tests flush and clear the persistence context before GET; UI tests navigate back into editing and fetch current values |
| AC9: booking isolation | `characteristicUpdatesNeverCreateDeleteOrModifyBookings` compares complete ordered JSON snapshots of the booking table before/after every update |

The AC9 test creates ten booking fixtures: pending, confirmed, changed, rejected,
and cancelled bookings for both the edited venue and a separate venue. Snapshots
include every column (IDs, foreign keys, status, notes, rejection reason), detecting
creation, deletion, and modification. The test covers each editable field, a combined
update, and a rejected update. Fixtures and updates roll back with the test transaction.
Unknown venue updates are separately tested to return 404.

Latest executed checks:

- Backend: 114 tests passed across `VenueControllerTest`, `VenueServiceTest`,
  `VenuePersistenceTest`, and `VenueMapperTest`, using real PostgreSQL.
- Frontend: 26 tests passed in `VenuePages.test.tsx`.
- Frontend production build (including TypeScript): passed.
- Frontend ESLint: passed.

Reproduce from the repository root (Java 25 and the local PostgreSQL service required):

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/csphere'
.\backend\mvnw.cmd -f backend/pom.xml -B -ntp '-Dtest=VenueControllerTest,VenueServiceTest,VenuePersistenceTest,VenueMapperTest' test
npm.cmd --prefix frontend test -- VenuePages.test.tsx
npm.cmd --prefix frontend run build
npm.cmd --prefix frontend run lint
```

Verification boundary: MockMvc tests exercise HTTP handling through real PostgreSQL;
React tests mock HTTP responses. These checks are not a live-browser end-to-end test.
A manual browser walkthrough should open the baseline 100-person venue, update capacity
to 150, save and reopen, then repeat for the other characteristics and Additional
information. Confirm unchanged values after each save. Database booking preservation
is established by the transactional integration test above, not by the UI tests.
