# VS16 local demo seed and manual testing

For Venue Staff, Event Coordinator, and anyone developing or reviewing the shared
booking-detail component. This fixture enables testing before the Coordinator
creation workflow exists. It does not implement that workflow.

## Location and sharing

- `backend/dev/seed/vs16-seed.sql`: creates one venue, three events and three pending bookings.
- `backend/dev/seed/vs16-cleanup.sql`: removes only the identified demo records.
- These are manual scripts, not Flyway migrations or startup operations.
- Commit/share these scripts and this guide. Pulling them does not modify anyone's
  database: each teammate explicitly runs them locally. Do not share database dumps
  or credentials. No existing venue UUID needs to be configured.
- The opt-in flag is an accident guard, not environment detection. Target only your
  local development database, never a shared or production database.

## Start and seed

PowerShell commands from the repository root:

```powershell
docker compose up --build -d
Get-Content -Raw backend/dev/seed/vs16-seed.sql | docker compose exec -T db psql -X -U postgres -d csphere -v ON_ERROR_STOP=1 -v vs16_local_dev=on
```

Wait for backend startup/Flyway migrations before running the seed. Check
`$LASTEXITCODE`: failure is nonzero and rolls back the transaction. On macOS/Linux,
use `cat` instead of `Get-Content -Raw`. Start the frontend with `npm run dev` after
normal dependency installation (see the root README).

## Reserved demo records

| Record | Fixed UUID | Identity |
|---|---|---|
| Venue | `16000000-0000-4000-8000-000000000001` | `[DEMO VS16] Seminar Room - local development only` |
| Event | `16000000-0000-4000-8000-000000000002` | `VS16 Demo Workshop`; organisation `VS16_LOCAL_SEED`; purpose `VS16 local development fixture` |
| Booking | `16000000-0000-4000-8000-000000000003` | Pending; notes `VS16_LOCAL_SEED` |

The venue has capacity 200, classroom/theatre layouts, step-free access/elevators,
projection/audio-visual facilities, operating information and entrance instructions.
The event expects 120 attendees on 24 September 2026, 14:00–17:00 Singapore time.
Dates are fixed; rerunning does not advance them. The event's required schema status
is `confirmed`, while the booking is `pending`: these are fixture values, not results
of an implemented approval/confirmation workflow.

Both event and booking reference the demo venue. No real venue/booking is reused.
Reruns preserve all test edits. Partial fixtures, reserved-ID collisions, changed
identity markers or changed relationships cause refusal without modifications.
Keep the venue address, event organisation/purpose, booking notes and foreign keys
intact during ordinary tests; they identify seed ownership.

## Open the component

1. Sign in with a seeded Venue Staff account (for example `vs1`; local dev password `123456`).
2. Venue Catalogue → `[DEMO VS16]` venue → **View venue details**.
3. Associated bookings → VS16 Demo Workshop → **View booking details**.
4. Compare venue information on the left with event requirements on the right.

Direct route after signing in as Venue Staff:
`/venue-staff/bookings/16000000-0000-4000-8000-000000000003`.

Read endpoints:
- `/api/venues/16000000-0000-4000-8000-000000000001/bookings`
- `/api/venue-bookings/16000000-0000-4000-8000-000000000003`

Future VS02 / Event Coordinator integration should use the same booking relationship
and detail route. No Organiser `event_requests` row is seeded. Shared authentication now protects API reads; venue-management ownership checks
remain deferred. VS16 does not alter the authorization configuration.

## Test current data, optional values and long text

Run this PowerShell command against the local database to change only the demo event:

```powershell
"UPDATE events SET expected_attendance=150, accessibility_needs='{}'::accessibilities[], equipment_requirements=NULL, venue_requirements=repeat('Keep aisles clear and arrange classroom seating. ',80) WHERE event_id='16000000-0000-4000-8000-000000000002' AND organisation='VS16_LOCAL_SEED' AND purpose='VS16 local development fixture';" | docker compose exec -T db psql -X -U postgres -d csphere -v ON_ERROR_STOP=1
```

Expect `UPDATE 1`. Navigate away and reopen the booking: there is no automatic polling.
Verify attendance 150, `Not specified` for accessibility/equipment, and readable
wrapped long requirements. Use Previous/Next to move between bookings without returning to the list. Check the booking counter, disabled end controls, and narrow viewport layout. Long requirements use normal page flow without a nested scrollbar.
Viewing must leave the booking status pending. Editing an Organiser request would
not change the current event; accepted-change application is separate work.

To restore defaults, cleanup then seed again. Seed alone intentionally preserves edits.

## Cleanup

```powershell
Get-Content -Raw backend/dev/seed/vs16-cleanup.sql | docker compose exec -T db psql -X -U postgres -d csphere -v ON_ERROR_STOP=1 -v vs16_local_dev=on
```

Deletes demo booking → event → venue in one transaction. Refuses if unrelated
bookings/events/event requests/equipment requests/equipment logs reference these
records. Foreign keys provide a further rollback safeguard. Do not use cascading
deletes to bypass a refusal. Inspect unexpected records/markers manually instead.
Repeated cleanup is a harmless no-op when all demo records are absent.

## Verification

Scripts are tested in a disposable database with the current local schema: opt-in
required, first run, rerun preserving attendance edits, cleanup, repeated cleanup,
unrelated venue preservation, dependency refusal, partial-ID collisions and changed
identity refusal. That verification database is removed afterward. A demo dataset
is then seeded in the normal local database for manual use.

See [booking API/component documentation](venue-booking-api.md) for read semantics,
future VS02 reuse and authorization limitations. This is component test data, not
verification of the real Coordinator-to-Venue-Staff workflow.

Verified 2026-09-22: all listed seed/cleanup safeguards passed in a disposable database; local API reads returned the demo venue (capacity 200), VS16 Demo Workshop (attendance 120), and one pending booking after seeding.


## Additional demo events

The seed now creates three event/booking pairs under the same demo venue:

| Event | Singapore date/time | Attendance | Event UUID suffix | Booking UUID suffix |
|---|---|---|---|---|
| VS16 Demo Workshop | 24 Sep 2026, 14:00–17:00 | 120 | `000000000002` | `000000000003` |
| VS16 Demo Seminar | 25 Sep 2026, 09:00–12:00 | 180 | `000000000004` | `000000000005` |
| VS16 Demo Planning Session | 28 Sep 2026, 10:00–12:00 | 40 | `000000000006` | `000000000007` |

All UUIDs use prefix `16000000-0000-4000-8000-`. The Planning Session has no
accessibility selections or equipment requirements, to demonstrate `Not specified`.
Rerun the existing seed command to add the two new pairs without resetting the
original workshop or venue. Each pair must be fully absent or valid; incomplete or
colliding pairs abort the entire transaction. Cleanup supports both the original
single-pair dataset and the expanded dataset and removes all reserved demo pairs.


## Pagination failure and keyboard checks

- Open a demo booking and use Previous/Next below the requirements panel. The URL
  changes within the app; there should be no document reload or panel collapse.
- With browser network throttling enabled, focus Next and press Enter. Focus should
  remain on Next during loading and after success, including at the final booking.
  Repeated Enter/Space while unavailable must not send extra detail requests.
- Block a booking-detail GET in browser developer tools, then navigate to that
  booking. Both panels and pagination remain, with an error explaining that the
  previously loaded booking is still displayed. Unblock the request and choose
  Try again: the panels remain during retry and update after success.
- Confirm initial navigation to a missing booking shows an error without details.

Automated verification on 2026-09-22: all 35 Venue Staff UI tests, scoped ESLint,
and the frontend production build passed. Browser scroll geometry remains a manual
check; the automated regression tests verify retained DOM panels and keyboard focus.
