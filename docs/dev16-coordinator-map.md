# DEV16 — Event Coordinator venue maps (1 November 2026)

User-authorised extension into EC venue search (EC04) and per-event venue requests
(EC03/EC-C2/EC10). It applies to every `/coordinator/events/:eventId/venue-booking`
route, including the named event. Application logic contains no fixed event ID.

## Layout and filtering

- `/coordinator/venue-search` retains its time/capacity/facility availability
  search and maps the returned venues. A local text filter matches name, address
  or additional information, ignoring case/outer spaces. Cards and pins use the
  same filtered array. Changing availability criteria clears previous results/pins
  until another search succeeds.
  Capacity uses the example placeholder **e.g. 100**. The shared venue-search
  field styles use the same light-grey `--ink-faint` token as Venue Staff for
  placeholders and empty date controls; entered values retain normal ink styling.
- The request page retains its desktop columns, shell margins and spacing:
  **What the event needs** on the left; map, search toolbar, **Choose venues** and
  the existing review on the right. The existing layout stacks below 900px.
  EC maps use the coordinator accent, paper/ink styling and a 340px canvas
  (280px on phones).
  Both EC map pages share Venue Staff's spacing values: 1.25rem section gaps,
  1rem list/control gaps, 1.5rem panel padding (1rem on phones), and .6rem/.7rem
  input padding. The values live in the shared map stylesheet and also drive the
  staff explorer; map toolbar/footer spacing uses the shared renderer directly.
- Request pins use the exact visible venue options. Text, **Show unavailable
  venues**, replacement eligibility and live refresh affect cards and pins
  together. No matches means no markers. Rooms without coordinates remain
  selectable in the list/review.
- Filtering hides pins without discarding selected venues from the review.
  Clearing a filter restores their selected highlights.

## Selection and shared locations

Browsing focus and request selection are independent. The existing approximately
25-metre grouping produces a numbered location marker, highlighted when any
visible member is selected. Opening it does not change request selection.
Individual rooms in the EC request popup are accessible checkbox toggles; only
selected rooms are checked/highlighted. Popup choices and cards call the same
selection function, retaining time defaults and replacement rules. Review removal
also updates the map. Normal requests allow multiple rooms; replacements allow one.

The general search page uses single map focus to highlight its corresponding
result card. Its **Show on map** action also focuses the room's marker/popup;
unlocated results display a location-unavailable label. Browsing/selecting does
not send a booking mutation. The explicit
Request button and server-side assignment, suitability, timing and conflict checks
remain authoritative. A map pin is not a booking availability guarantee.

## Reuse and access boundaries

`VenueMap` now accepts independent focus and selected IDs. `VenueMapPanel` shares
its lazy-loading boundary; common styling lives in `VenueMap.css`.
`CoordinatorVenueMap` supplies EC sizing/accent/copy, and `venueMatchesSearch`
keeps both EC lists and maps on the same text filter. Venue Staff retains its
single-selection behaviour and styling.

EC uses existing `GET /api/venues/search` and assigned-event
`GET /api/events/{eventId}/venue-options` responses, including saved coordinates.
It never calls staff map count/booking-summary endpoints. No API permissions,
schema, migration, seed, coordinates or booking-decision rules changed.

The live backlog could not be retrieved through the available tool and local
`Materials/Project` is absent. This extension follows the user's explicit scope,
existing EC workflows and repository contracts. Tests retain existing story tags
and use DEV16 without inventing acceptance-criterion numbers.

## Verification

- **1,055 frontend tests passed**, 100 files.
- The shared map/loading panel, EC adapter, text helper and existing staff/filter
  gates retain **100% lines, statements, branches and functions**; no thresholds
  were weakened and no coverage-ignore comments were added.
- Component cases cover filter/pin equality, no matches, absent coordinates,
  independent browsing/selection, hidden selections, map/card/removal sync,
  unchanged batch payloads, single replacement choice, denied reads and refreshes.
- **10 Chromium checks passed**: three new EC checks, staff map/layout/gestures,
  and the multi-room approval/safety journey. They use the supplied route with
  controlled fixtures plus a real assigned event/venue/booking against PostgreSQL.
  Desktop/mobile screenshots were visually inspected.
- Production build, lint and traceability passed. No backend source changed;
  browser APIs used the existing package and a fresh isolated database.
- **Five additional browser regressions passed** for holds, structured hours,
  requirement reviews, live suitability and unavailable-period release/history.
- The final three EC browser checks also passed after adding result-card
  **Show on map** focus. Traceability reports **261 files / 79 stories**, with
  zero untraced files or high-priority story gaps. The isolated test services were
  stopped after verification; application data was not changed and no commit/push
  was made.

The spacing alignment passed 63 relevant component tests, six Chromium checks,
production build and lint. Rendered comparisons at 1440px and 390px confirmed
matching staff panel padding, section/list gaps, input padding and map chrome,
with no horizontal overflow.

Local evidence uses `logs/dev16-ec-map-*` and Playwright's `test-results`.
Run `npm run test:coverage -- --pool=threads --maxWorkers=4`, `npm run build`,
`npm run lint`, and `npm run test:e2e -- coordinator-venue-map.spec.ts` against
the built application.
