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

## Current access limitation

These endpoints currently have no server-side role or identity enforcement.
The existing frontend role selector does not authenticate API callers. The
interim access mechanism remains undecided in decision-log D6a/Q2; this API must
not be described as restricted to Venue Staff or as completing authentication.
The Venue Staff routes (including edit) use `useRoleGate('venue-staff')`.
This is a UI restriction only: backend identity, role enforcement, and venue-management
permissions do not exist. **Full VS07 AC1 is not satisfied** and depends on separate
authorization/ownership work under D6a/Q2 and AU04/DEV05. Do not mark VS07 fully
accepted on the strength of the UI gate. The list returns the whole catalogue
without pagination.

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
