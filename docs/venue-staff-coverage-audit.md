# Venue Staff workflow and coverage audit — 31 October 2026

The audit covers the `/venue-staff` console/map, catalogue, create/edit/details,
booking decisions, holds, assessments, requirement reviews, timing conflicts,
calendar, operational issues, unavailability and release/history. Related backend
contracts were checked against real PostgreSQL.

Reviewed `AI_Context.md`, product context, the decision log, testing strategy and
the venue API, booking, hours, calendar, unavailability, holds, requirement-review,
multi-venue and presentation guides. DEV16 identifies this branch follow-up.
Existing story tags/assertions were retained; no external AC numbers were invented.

## Added workflow evidence

| Workflow | Cases |
|---|---|
| Map | Keyboard/shared-room selection, numbered markers, reset, tile retry, date/past bookings, filter/view controls, queued selection after validation |
| Catalogue/forms | Identity/duration limits, independent operating days, duplicate submit, failed forms, late editor responses, legacy labels |
| Booking decisions/details | Timing confirmation, hold arriving during confirmation, failed conditional updates, missing locked records, alternative retry, late responses, scroll height, pagination, coordinator/replacement context |
| Holds | Retained history, conversion without returned history, long timers, server-derived expiry, duplicate submit, mutation/refresh failures, unavailable-period conflicts |
| Assessments/reviews | Malformed data, layout changes, stale reviews, comment validation, historical labels, input recovery, independent sibling reviews |
| Timing/calendar | Scan/notification feedback, empty and failed scans, conflict intersections, catalogue retry/removal, Singapore midnight/continuation labels |
| Unavailability/settings | Midnight revalidation, exact second/millisecond input, preview acknowledgement, independent history retry, cancellation, settings retry, preserved booking snapshots |
| Operational issues | Period/length validation, Singapore instants, timed/untimed details, read/save/refresh failure, late responses |

New files include `VenueSharedPanels.test.tsx`, `BookingApprovalCoverage.test.tsx`,
`useVenueRead.test.tsx`, `VenueStaffFailureResponseTest`,
`VenueStaffCoverageFlowTest` and `VenueBookingDecisionGuardsTest`. Existing staff
and map test files also gained boundary/conflict/failure cases.

## Corrections found by tests

- An empty timing-conflict scan retains its confirmation instead of disappearing.
- Unavailability validation accepts the millisecond suffix produced by normalised
  `datetime-local` values while continuing to reject invalid dates/seconds.
- Explicit venue form state normalises optional API values once, removing
  redundant undefined fallbacks without changing requests or validation rules.
- Typed hold actions require their target hold for release/conversion.

## Verified local results

| Check | Result |
|---|---|
| Full frontend suite, with coverage gates | **1,045 tests passed**, 98 files; exit 0 |
| Venue Staff frontend (29 files) | **100%** lines (780/780), statements (1,034/1,034), functions (317/317), branches (1,361/1,361) |
| Map frontend (4 files) | **100%** lines (115/115), statements (153/153), functions (64/64), branches (159/159) |
| Shared venue filters (1 file) | **100%** on all four metrics |
| Clean backend verification on PostgreSQL | **1,923 tests passed**, no failures/errors/skips; packaged successfully |
| Backend map coverage gate | **100%** instructions (300/300), lines (26/26), branches (16/16), methods (17/17); gate passed |
| Final packaged-application browser checks | **15 journeys passed**: desktop/mobile map and gestures, approval/safety, holds, hours, reviews, staff presentation, live suitability, release/history and scroll preservation |
| Frontend production build and lint | Passed; existing bundle-size advisory remains |
| Traceability | **258 files, 79 stories**, zero untraced files/high-priority gaps |

Full application diagnostics remain **94.60% frontend lines / 87.53% branches**
and **96.44% backend lines / 83.03% branches**. These include other roles and
shared backend services. They must not be described as repository-wide 100%.

HTML reports are generated at `frontend/coverage/index.html` and
`backend/target/site/jacoco/index.html`. Ignored local evidence uses the
`logs/venue-staff-coverage-*` prefix; the backend gate log is
`logs/venue-staff-map-coverage-gate.log`. The gate was separately verified against
the completed clean execution data; skipping tests for that gate-only packaging
check is not counted as another test run.

## Interpreting the results

The frontend gate covers all 34 TS/TSX source files under `features/venueStaff`,
`features/venueMap` and `components/venue`. It requires 100% statements, branches,
functions and lines. The backend `verify` gate requires 100% instructions,
branches, lines and methods in `com.example.connect_sphere.venuemap`.
Shared backend workflow services were regression-tested but do not receive a
blanket 100% claim. Generic shared UI/auth infrastructure and other roles remain
in the full diagnostic report; CSS/layout is verified through browser checks.

Line, statement, function and branch coverage are separate measurements. Neither
100% lines nor a passing workflow proves every branch or correctness. Component
tests double transport/map boundaries; PostgreSQL and browser journeys exercise
real contracts. No tests were skipped/deleted/weakened, and no coverage-ignore
comments were added. Unimported source files remain included in reports.

The full frontend report includes other roles. Backend coverage includes shared
coordinator/venue code and defensive database/infrastructure paths. For example,
the SHA-256-unavailable catch in `AvailabilityService` cannot occur with the normal
Java runtime's required algorithm provider. Missing-row and conditional-update
guards remain visible rather than being hidden by package exclusions.

The remaining calendar frame null check was removed because every loading, empty
and populated calendar state renders that frame before its handlers can run.
This is an explicit DOM invariant; it does not remove a stale-response guard.

These are local results, not CI/branch-protection/deployment claims. Earlier
presentation-audit counts are historical execution records.
All checks used an isolated test database and separate application ports. The
temporary servers were stopped afterward. No application database was changed,
and no commit or push was made.

## Reproduction

Use a disposable PostgreSQL database; never reset application data for coverage.
Set the backend datasource environment as described in its README, then run:

```powershell
cd frontend
npm ci
npm run test:coverage -- --pool=threads --maxWorkers=4
npm run lint
npm run build
cd ..
./backend/mvnw.cmd -f backend/pom.xml -B -ntp clean verify
python .github/scripts/traceability.py --check
```

Run the staff browser specs against the packaged backend/built frontend per
`frontend/e2e/README.md`: map/layout, tentative holds, requirement review,
live suitability, structured hours, staff presentation, unavailability release
and booking scroll preservation.
