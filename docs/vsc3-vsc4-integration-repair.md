# VS-C3 / VS-C4 integration repair — 18 October 2026

## Investigation

PR [#56](https://github.com/spikeypear-bot/cSphere/pull/56) introduced VS-C4 at
`6819157`, merged into main as `e1850e7`. It enriches conflict read models, adds
fresh read-only conflict retrieval and shared conflict cards, and highlights
calendar segments, including partners outside the visible dates. The existing
scan retains notification deduplication without changing booking statuses.

PR [#57](https://github.com/spikeypear-bot/cSphere/pull/57) introduced VS-C3 at
`535945e`, then merged main at `cdfea66`. VS-C3 adds persisted hold attempts,
server-clock expiry, release and conversion, expiry notifications/activity,
availability checks and booking/calendar UI. The local branch initially stopped
at `535945e`; it was fast-forwarded to the existing PR head before repair. No new
commit was created, and no remote branch was changed.

The failed [CI run](https://github.com/spikeypear-bot/cSphere/actions/runs/39072775403)
tested PR merge `3af84f9`. Its logs confirm four lint errors. The integration
contains additional regressions that lint alone could not expose:

| Integration damage | Repair |
|---|---|
| VS-C4's `bookingLinks` option was added to the summary, but referenced from VS-C3's extracted conflicts component without a prop | Forward the option and give the conflicts component an explicit default |
| The timing editor gained a summary with no import and an unclosed conditional JSX expression | Keep the original complete editor and its existing eligibility guard; render links in the requirements panel where VS-C3 moved the summary |
| The backend schedule replaced VS-C3's `holds` with VS-C4's `activeConflicts` | Return both fields in bounded and unbounded APIs |
| The calendar dropped hold deadlines and five-second polling | Restore both while retaining conflict cards, off-screen partners and selection by ID |
| Conflict rendering compared `confirmed` against VS-C3's `approved` entry type | Use `approved`, consistent with the booking model and existing status labels |
| Calendar entries and legend lost the attributes used by shared status colours | Restore `data-booking-status` alongside conflict attributes |
| Two VS-C4 calendar tests still expected the old Confirmed label | Assert Approved and retain every conflict/highlight assertion |

## Preserved behaviour and acceptance evidence

- Availability retains the existing shared `EffectivePeriod` calculation and
  strict overlap comparison. Confirmed booking snapshots include setup and
  turnaround. One SQL blocking query includes approved bookings and active
  pending-booking holds with `expires_at > serverNow`, excluding cancelled and
  completed events. Expired/released holds never block approval, even before
  the scheduled job persists expiry.
- Existing event locks and venue locks remain intact across holds, approvals,
  conversion, timing edits, cancellation and rejection. Conversion excludes
  its own booking's hold and approves that booking atomically. Duplicate
  conversion, competing holds and hold-versus-approval race tests remain enabled.
- Existing hold validation, operating-hours/unavailability checks, staff RBAC,
  history, transactional notifications and expiry retry behaviour are preserved.
- Existing VS-C4 effective-period conflict retrieval, cards, links, calendar
  highlighting, off-screen partners and conflict resolution remain intact.
  Conflict reads do not mutate bookings or send notifications. Timing edits
  retain stale-write protection and permit conflict-reducing duration changes.
- New backend regressions exercise both schedule APIs with an active hold and
  a legacy turnaround conflict together, expiry before worker execution,
  manual release followed by competing approval, and conversion occupancy.
- Frontend regressions cover local expiry despite a stale response, expiry
  timestamps/status/navigation, polling, preserved colour attributes and
  conflict markers, staff detail links and opt-in links in shared summaries.
- V29 follows V28 without a version collision. Its table, foreign keys, status
  checks, positive intervals and unique active-hold index are compatible with
  main. Existing migrations were not edited. Tests use a disposable PostgreSQL
  18 database on port 55432; development data was not reset.

## Modified files

Paths are relative to the repository root.

| File | Purpose |
|---|---|
| `backend/src/main/java/com/example/connect_sphere/venueavailability/AvailabilityModels.java` | Schedule contains holds and active conflicts |
| `backend/src/main/java/com/example/connect_sphere/venueavailability/AvailabilityService.java` | Populate both schedule fields for either range mode |
| `backend/src/test/java/com/example/connect_sphere/venuebooking/VenueHoldFlowTest.java` | Integrated schedule, expiry, release/approval and conversion regressions |
| `frontend/src/components/BookingTimingSummary.tsx` | Pass navigation option into the conflicts component |
| `frontend/src/features/venueStaff/BookingTimingEditor.tsx` | Restore valid complete TSX editor without duplicate summary |
| `frontend/src/features/venueStaff/EventRequirementsPanel.tsx` | Enable staff conflict navigation at the existing summary location |
| `frontend/src/features/venueStaff/VenueSchedulePage.tsx` | Restore deadlines, polling, status colours and compatible conflict status |
| `frontend/src/features/venueStaff/BookingDetailsPage.test.tsx` | Staff links and single-summary/editor regression |
| `frontend/src/features/venueStaff/TimingConflictReview.test.tsx` | Shared summary links remain opt-in |
| `frontend/src/features/venueStaff/VenueSchedulePage.test.tsx` | Integrated calendar regressions and label compatibility |
| `frontend/e2e/tentative-holds.spec.ts` | Browser assertions for calendar refresh after release and conversion |
| `frontend/e2e/failure-paths.spec.ts` | Exact status assertions prevent the event name Rejected Gala from falsely satisfying rejection completion |
| `docs/venue-calendar-api.md` | Document combined response and current labels/refresh behaviour |
| `docs/test-traceability.md` | Regenerated story/test matrix |
| `docs/vsc3-vsc4-integration-repair.md` | Investigation, scope and verification record |

## Verification

| Check | Result |
|---|---|
| Frontend `npm ci` | Passed; lockfile unchanged |
| Frontend `npm run lint` | Passed |
| Frontend `npm run build` | Passed; TypeScript and Vite production bundle |
| Targeted frontend tests | 75 passed across hold panel, schedule, timing editor, details and conflict review; the later details regression is included in the full run below |
| Frontend `npm run test:coverage -- --maxWorkers=2` | 488 tests passed, 49 files; lines 90.04%, branches 81.66% |
| Frontend `npm audit --audit-level=high` | Passed; zero vulnerabilities |
| Targeted backend tests | 208 passed, no failures/errors/skips |
| Backend `mvnw.cmd -B -ntp clean test -DexcludedGroups=integration` | 557 passed, no failures/errors/skips |
| Backend `mvnw.cmd -B -ntp verify -Dgroups=integration` | 541 passed, no failures/errors/skips; packaged jar and JaCoCo report generated |
| Backend coverage | Lines 95.9%, branches 82.9% |
| Flyway V28 and V29 | Both successful in disposable PostgreSQL; all migrations validated |
| Packaged-jar smoke | All five CI scenarios passed: health, bad-password 401, organiser login, request-list read, forbidden coordinator-events 403 |
| Frontend `npm run test:e2e` | All 10 Chromium browser tests passed against the production bundle and packaged jar, including hold release/conversion calendar refresh and desktop/mobile expiry scroll preservation |
| Traceability | Passed; 116 test files, 49 stories, zero untraced files; 14 pre-existing high-priority story gaps remain |
| `git diff --check` | Passed |

Backend checks explicitly select the installed Java 25 JDK. Initial environment
attempts exposed an older `JAVA_HOME`, sandbox denial of localhost connections,
and sandbox temporary-file access errors. The successful runs correct those
environment settings. A default-parallel frontend coverage attempt timed out
across unrelated suites under resource contention and was stopped. Limiting the
successful full run to two workers preserves all tests, assertions and timeouts.

Local checks do not replace the required remote CI checks on the PR. No CI
rules, migrations, dependency versions, test assertions or test exclusions were
weakened. GitHub-only SBOM generation/scanning is left to the unchanged CI
workflow. The first full browser run passed nine tests, including the expanded
hold journey and desktop/mobile expiry checks, and exposed a separate existing
test race: substring status assertions matched the event name Rejected Gala,
allowing the organiser read before the rejection committed. Both assertions now
require exact Rejected text, preserving the original status/reason checks.
The final complete browser run passed all 10 tests in 45.2 seconds with retries
disabled. No product changes outside VS-C3/VS-C4 were needed.

## Review readiness

The repaired VS-C3 branch is ready for PR review with both stories preserved and
all available local CI-equivalent checks passing. Remote GitHub CI and its SBOM
steps still need to run after an approved commit/push. No new commit or push was
made. Temporary application/preview processes and the disposable test database
were stopped after verification. Logs remain under ignored
`logs/integration-repair-*`; coverage remains under `frontend/coverage` and
`backend/target/site/jacoco`.
