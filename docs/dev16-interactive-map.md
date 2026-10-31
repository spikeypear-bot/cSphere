# DEV16 — Venue Staff map, first release

For the later staff-facing copy, placeholder, card/spacing and seeded-note review,
see [Venue Staff presentation and notes](dev16-venue-staff-notes.md).
For the subsequent full workflow/test review, see the
[Venue Staff coverage audit](venue-staff-coverage-audit.md).

The `/venue-staff` console retains its four existing workflow cards. The interactive
venue catalogue and map appear below them on the same page. The console uses the
existing centred 900px shell and its original desktop margins/padding. Existing catalogue, booking approval,
availability calendar and detail routes remain available.

## Experience

- Desktop uses approximately 38% catalogue / 62% map. Below 900px, List view / Map
  view buttons switch the same filtered results. Venue details appear below the split.
  The desktop catalogue scroll area follows the full map card height, including its
  toolbar, footer and any tile-error message. Mobile list height remains independent.
- Search matches saved venue name or address, ignoring case and surrounding spaces.
  Capacity is a minimum (inclusive), and every selected facility must be present.
  Inputs and the always-visible facility checkbox group share `VenueSearchField`
  and `VenueFacilitiesFilter` with the coordinator venue-search workflow.
- Touchscreen pinch and mouse-wheel/touchpad pinch zoom are enabled over the map,
  with wheel zoom anchored at the cursor. Gestures are handled by Leaflet locally.
- `Events on date (SGT)` means a visible booking overlaps that Singapore calendar day,
  using half-open intervals. A booking ending at midnight does not match the next day.
  This is a discovery filter, never an availability or suitability check.
- Selecting a card focuses its marker; selecting a marker selects its card. Rooms
  within approximately 25 metres share a marker with individually selectable room
  buttons. Original coordinates, room IDs and database records are unchanged.
- Missing or invalid coordinates remove only the map marker. The room stays in the
  catalogue, search and details. Cards show an explicit location-unavailable label.
- The selected room reuses `VenueDetailsPanel` in read-only mode and links to the
  existing venue and booking detail pages. No booking decision is performed by the map.
- Approved venue bookings do not mean the event itself is confirmed. Pending requests,
  active tentative holds and `attention` records retain their distinct shared labels.
  Cancelled/rejected/replaced bookings and cancelled events are excluded.
- Each booking remains a separate row and links by booking ID, even when the same
  event has several bookings at one venue or is booked at multiple venues.
- Upcoming/in-progress rows are chronological. Past approved bookings are opt-in,
  grouped separately; selecting a historical date also includes its approved history.
  Completed events never appear as upcoming. Expired unswept holds are not tentative.

## Data and authorisation

The existing authenticated `GET /api/venues` supplies saved characteristics and
nullable coordinates. No new venue storage, migration, geocoding or seed data is added.

Two read-only endpoints are explicitly restricted to `ROLE_VS` in `SecurityConfig`:

| Endpoint | Response |
|---|---|
| `GET /api/venue-staff/map/activity?date=YYYY-MM-DD` | Per-venue `venueId`, distinct approved `eventCount`, `pendingBookingCount`, and `matchingBookingCount` |
| `GET /api/venue-staff/map/venues/{venueId}/bookings?date=YYYY-MM-DD&includePast=false` | Booking ID, event ID/name, saved booking start/end (legacy event-time fallback), booking status, active hold deadline, server time and past flag |

`date` is optional. Without it, counts cover upcoming/in-progress bookings. With it,
counts and details use the same Singapore-day overlap predicate. Missing activity
rows mean zero matching bookings. Counts never include private event fields. Full
booking details are not loaded when opening the console; the selected room's summaries
load on demand. Existing staff-wide venue access remains the authorised scope.

Reads use the existing bearer-aware API client and venue-read hook, fresh on opening,
manual refresh and window focus, with 30-second refresh while mounted. Data can be up
to one refresh interval old. Current data stays visible while a refresh is pending;
failed/denied reads hide affected old results and offer retry. Responses for an old
selection or filter cannot replace the current view. A hold deadline removes the
tentative display even if the subsequent refresh fails.

Future coordinator, lead or attendee access needs an explicit server-side scope and
field policy before exposing these endpoints. The presentation map is reusable and
contains no role-specific API calls; the first-release map is mounted only by the
existing gated Venue Staff home.

## Dependencies and tiles

`react-leaflet` 5, `leaflet` 1.9 and `@types/leaflet` are recorded in the frontend
manifest and lockfile. Teammates run `npm ci` in `frontend` after pulling. Leaflet CSS
loads before application CSS. The map library is a separate lazy-loaded build chunk.
Custom div markers avoid bundler-dependent default marker-image URLs.

The default tile URL is `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. Attribution
stays visible, tiles use normal browser caching and no bulk download/prefetch is added.
`VITE_MAP_TILE_URL` and `VITE_MAP_TILE_ATTRIBUTION` can select another compliant provider;
see `frontend/.env.example`. Tile failures show retry without disabling the catalogue.
A ResizeObserver invalidates the map size when its container changes, including mobile
view switching. The initial Singapore view is resettable. External location search is
outside this release.

## Verification

All new tests trace to DEV16; permission checks also trace to AU04. No canonical DEV16
acceptance-criterion numbers were supplied, so the tests do not invent numbers.

- `VenueMapTest`: real PostgreSQL/MockMvc checks for multi-venue associations, distinct
  event counts, repeated booking IDs, saved status refresh, cancelled/completed events,
  Singapore-day boundaries, legacy time fallback, hold expiry, missing records, malformed
  input, role/authentication denial and unchanged venue/event/booking/hold snapshots.
- `mapData.test.ts`: coordinate validation/grouping and combined filtering boundaries.
- `VenueMap.test.tsx`: invalid selections never move the viewport, valid selections
  focus their coordinates, and container resizing preserves the centre with observer cleanup.
- `VenueMapExplorer.test.tsx`: console placement, lazy booking reads, card/marker
  selection, navigation IDs, filters/clear, empty/missing-coordinate states, API failures,
  stale data removal and hold deadlines. The map presentation is stubbed in these tests.
- `venue-map.spec.ts`: real backend and persisted catalogue, real Leaflet controls,
  shared-room selection, desktop/mobile sizing, keyboard marker selection and tile failure.
  Tile responses are intercepted for deterministic browser checks; provider availability
  and visual cartography are not asserted.

Run the existing frontend lint/build, relevant frontend/backend regressions, generated
diagram checks and `.github/scripts/traceability.py --check` as part of verification.
The backend tests use an isolated local database rather than modifying application data.

## Executed verification — 31 October 2026

| Check | Result |
|---|---|
| Frontend regression suite | 94 files, 944 tests passed |
| Final focused map checks, including the subsequently added viewport tests | 3 files, 15 tests passed |
| Backend map, venue/controller/search/booking-read regressions and generated diagrams | 121 tests passed; packaged backend built |
| Real Chromium desktop/mobile/keyboard/tile-failure journeys | 2 passed |
| Frontend production build and ESLint | Passed; existing large-main-chunk advisory remains |
| Story traceability | 250 test files, 79 stories, zero untraced files |
| Live tile visual review | Real OpenStreetMap tiles loaded; desktop/mobile previews inspected |

The full frontend regression run used `npm test -- --maxWorkers=2 --pool=threads
--reporter=verbose`: Windows fork-worker runs stalled, while the thread-worker run
completed. No retries, test timeouts, assertions or CI configuration were weakened.
The final focused map run covers the latest scroll and coordinate/resize safeguards.

Backend verification used Java 25 and PostgreSQL 18.3 on a dedicated loopback cluster
at port 55417, database `dev16_map_verify`. The packaged application and built frontend
were served temporarily on ports 18016/4176 for browser checks. No application database,
seed payload, migration or booking-decision implementation was changed. No commit or
push was made; these results are local verification, not CI results.

Evidence lives in ignored `logs/dev16-map-*` logs and preview images, Maven's
`target/surefire-reports`, and the browser test output directory.

### Desktop spacing and gesture follow-up — 31 October 2026

The user requested the original left/right spacing after reviewing the first release.
The 1440px map-only shell override was removed, restoring the existing centred 900px
shell and original padding. The map remains below the existing workflow cards.

Coordinator search and the staff map now share the extracted `VenueSearchField` and
`VenueFacilitiesFilter`, including the coordinator's existing input and fieldset styles.
The map's facilities are always visible. Coordinator availability-search parameters,
validation and search submission remain the same. The map continues to filter live
catalogue results and scheduled booking dates.

Leaflet's `scrollWheelZoom` and `touchZoom` options are enabled. Wheel/pinch events
over the map zoom it around the cursor; touchscreen two-finger pinch is also enabled.

Verification: 38 relevant component/routing tests passed, plus 3 Chromium checks in
`venue-map-layout.spec.ts` covering the 900px margins, matching coordinator input
styles, pinch-style Ctrl-wheel zoom in/out without page zoom, and touchscreen pinch
in/out with no mobile overflow. Browser gestures are simulated with Chromium input
events; no physical touchpad was used. API and tile responses are controlled fixtures
for these UI checks. Production code continues to read persisted API data.
Build, lint and traceability passed (251 files, zero untraced files). No backend or
database changes were required. Logs use the `logs/dev16-map-spacing-*` prefix.
