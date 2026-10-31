# Testing strategy

How ConnectSphere is tested, and how every test traces back to a user story. It applies IS212 Week 4 (test case design), Week 6 (automated testing #1 and CI) and Week 9 (test doubles, integration, E2E, continuous delivery).

The rubric's top band is the target: *"comprehensive and traceable, combining appropriate unit and integration/end-to-end tests for normal, boundary, conflict, and failure scenarios. 100% coverage unless not possible and stated why."*

## 1. The layers, and what each one proves

| Layer | Tool | Real | Doubled | Proves | Runs |
|---|---|---|---|---|---|
| **Unit** (`@Tag("unit")`) | JUnit 5 + Mockito | the one class under test | its collaborators (stubs/mocks), the clock | a rule in isolation: boundaries, refusals, who is notified | your machine (seconds); CI `backend-build` |
| **Integration** (`@Tag("integration")`) | Spring Boot test + MockMvc | controllers, security filter chain, services, JPA, **PostgreSQL** | nothing inside the app | the parts work together across the boundary: SQL, constraints, locking, role rules, JSON contract | CI `backend-build` (Postgres service container) |
| **Component** | Vitest + Testing Library (jsdom) | the React component and its children | `fetch` (`stubApi`) | what a user sees and can do on one screen | your machine; CI `frontend-build` |
| **End to end** | Playwright (Chromium) | the **packaged** jar, the **built** frontend, PostgreSQL | nothing | the journey a user takes across roles still works | CI `e2e`, after `package` |
| **Smoke** | `.github/scripts/smoke.sh` (curl + jq) | the packaged jar | nothing | the thing we would ship starts and answers roughly right | CI `smoke`, after `package` |

Most tests are unit tests, fewer are integration tests, and the fewest are E2E: the test pyramid. E2E tests are slow and the most prone to flakiness, so they cover only the journeys a user would notice breaking:
- the **golden path**: request → assignment → approval → venue → safety check → confirmation → organiser sees arrangements;
- two **failure paths**: a rejected request; the Safety Officer requesting changes;
- sign-in.

### Why integration tests use a real database (not a fake)

Week 9 recommends disposable real infrastructure when an in-memory substitute would behave differently from production. Ours depends on PostgreSQL specifics: enum types, check constraints, `SELECT … FOR UPDATE` row locks and Flyway migrations. So integration tests run against real PostgreSQL (Docker locally, a service container in CI). There is no fake database.

## 2. Test doubles: which, and why

| Role | What it does | ConnectSphere example | How to assert |
|---|---|---|---|
| **Dummy** | Must be passed in, but is never used on this path | The repositories in `EventLifecycleServiceTest.withdrawingNeedsAReason`: a blank reason is refused before any of them is touched | `verify(events, never()).findForUpdate(any())` |
| **Stub** | Returns canned answers so the test controls an input | `availability.affected(booking)` stubbed to `true` to show readiness says "1 venue has become unavailable"; `EquipmentRequestLine` stubs in `EventArrangementsServiceTest` | Assert on **state**: the result, the status, what was (not) saved |
| **Mock** | Checks that an interaction happened, with the right arguments | `verify(notifications).createReturnedToPlanningNotification(COORDINATOR, eventId, "Town Hall", reason)` | Assert on **behaviour**: exact recipient, `times(1)`, `never()` on failure |
| **Fake** | A working, simplified implementation | Not needed. We use real PostgreSQL instead (above). Frontend `stubApi` is a stub plus a spy (canned responses, records calls), not a fake. | n/a |

Rules:
- **Mock when the requirement is the interaction.** Examples: who is notified, nothing saved on failure, confirming never touching bookings (EC08 AC33).
- **Stub when you only need to control an input** and then check the result.
- **Never mock the unit under test.** A test that stubs `service.confirm()` and then checks it returned what it was stubbed to proves nothing; Week 9 shows this as the classic agent-generated "passing" test.
- **Doubles can agree with each other and still be wrong about reality** (the lecture's dollars-vs-cents example). Component tests stub `fetch` with hand-written JSON, so a renamed field passes them. The integration and E2E layers exist to catch exactly that.

## 3. Traceability: every test names its story and AC

- **Backend:** each test class has a story tag for every story it covers (`@Tag("EC08")`, `@Tag("EO04")`), next to its layer tag. Test methods for a story's ACs carry a display name such as `@DisplayName("EC08 AC26, AC27 (conflict): prerequisites are re-checked …")`.
- **Frontend:** story IDs appear in the `describe`/`it` title (`it('EO04 AC14 (boundary): …')`), or a `// @stories EC03 EC-C2` header for files that do not name them.
- **Case types** in names follow the rubric and Week 4: `(happy)`, `(negative)`, `(boundary)`, `(conflict)`, `(failure)`, `(cross-cutting)`.
- **Integration and E2E tests** usually cover several stories. Tag every one of them.
- **`.github/scripts/traceability.py`** generates `docs/test-traceability.md` (story → test files by layer, ACs referenced, case types). It lists High-priority stories with no test yet. CI's `traceability` job fails if any test file names no story.
- **Week 4 test cases** (the Sheet's *Test Cases – Sprint N* tabs) list, under Remarks, the automated tests that execute them. Repository copies live in `docs/test-cases/`.

**Adding a test:** write the AC's expected value from the AC text (never from what the code returns). Name it `STORY ACn (case): what it shows`. Make sure a plausible wrong implementation would fail it. Run `python .github/scripts/traceability.py`.

## 4. Expected values, mutants and generated tests

- **Expected values come from the acceptance criteria**, not from running the code (Week 6). The same applies to Playwright codegen and "healed" tests: they record what the page showed. Re-check every assertion against the AC before keeping it.
- **Think in mutants.** For every test, ask what wrong implementation it would catch. Done for this sprint's stories:
  - removing EO04's "approved bookings only" filter → caught by `onlyApprovedBookingsAreShown`;
  - shortening the autosave debounce → caught by the wizard's fake-timer test (the old 1.7 s sleep could not catch it);
  - removing the print stylesheet rule → **only** the E2E print step caught it (all 43 component tests stayed green). That is why a few E2E tests exist.

## 5. Flaky tests: fix them, never retry them green

The causes (Week 9): timing, test order and shared state, time and date, randomness, external services, concurrency.

| Fix | Where we use it |
|---|---|
| Wait for a **condition**, not a duration | Playwright `expect(…)`; Testing Library `findBy…`; health polling in CI and `smoke.sh` |
| **Inject the clock** | `Clock` in `JourneyStatusResolver` and `EventLifecycleService` (fixed in unit tests); `vi.useFakeTimers()` for the wizard's autosave debounce |
| **Fresh state per test** | rolled-back `@Transactional` tests; unique names (`uniqueName()`, `Date.now()`) plus cleanup for committed flow tests |
| Double the network boundary | `stubApi` in component tests |

Anti-fixes are not allowed: `Thread.sleep`, `setTimeout` waits, `waitForTimeout`, retries (`retries: 0` in `playwright.config.ts`), weakened assertions, skips.

Two examples caught this sprint, both **timing**, both fixed by waiting for a condition:
1. 1 run in 18 hung because the wizard's **Next** saves before moving on, and the test clicked it twice without waiting. The fix waits for each step's own field (`frontend/e2e/support.ts`).
2. 1 run in 6 failed because the safety-changes setup opened the coordinator's page before Venue Staff's approval had been saved. The fix waits until the booking is `approved` on the server before the next role acts.

After both fixes, 18 of 18 runs passed (`--repeat-each=3`) on a fresh packaged build.

## 6. Coverage

The [Venue Staff coverage audit](venue-staff-coverage-audit.md) records the current
scope, added evidence, corrections and measured results. Earlier presentation-audit
test counts are historical execution records.

- **Where to see it:** every CI run publishes backend (JaCoCo) and frontend (Vitest V8) line and branch coverage on its summary page, with the HTML reports as artifacts (`.github/scripts/coverage-summary.py`).
- **General reporting is diagnostic:** coverage shows code no test executes, and says nothing about whether a test checks the right thing. Modules without explicit thresholds do not fail the build on coverage.
- **Venue Staff/DEV16 gate (user-requested, 31 October 2026):** `frontend/vite.config.ts` requires 100% statements, branches, functions and lines for `src/features/venueStaff/**`, `src/features/venueMap/**` and `src/components/venue/**`. `backend/pom.xml` requires 100% instructions, branches, lines and methods for the `venuemap` package during `verify`. Other backend/shared and role modules retain diagnostic reporting. No new coverage-ignore comments or package exclusions were added.
- **Target:** 100% for the classes behind a story, or stated why not (below). Uncovered branches found this way are either a missing boundary/failure test or dead code to delete. Examples:
  - `formatDate(null)`, a Confirmed-check that could never be false, and a "no approved venue" branch that confirmation makes impossible were all removed rather than tested.

### Stated coverage exclusions

| Code | Why it is not covered |
|---|---|
| `EventReadinessPanel.tsx`: the non-`ApiClientError` branch of `act()` | `apiClient` turns every failure, network included, into an `ApiClientError`, so this only guards against a programming error and no test can reach it. It is marked `/* v8 ignore … */` with the reason in the code. |
| Production constructors and the after-commit hook in `EventLifecycleService` / `JourneyStatusResolver` | Not reached by unit tests (which pass a fixed clock and have no transaction), but executed by the integration tests. They are covered in the combined report CI publishes. |
| AT04 `AttendeeRegistrationQueryService` defensive missing-event guards | Valid database rows cannot take these paths because the registration event FK applies. Unknown registration status and missing start fallbacks now belong to the shared AT08 withdrawal policy and are covered by its boundary tests. |
| AT04/AT08 `AttendeeRegistrationDetailsDto.eventDetailsAvailable = false` for an owned registration on a never-confirmed event | AT01 creates identified registrations only on confirmed events; ownerless legacy EO10 rows are deliberately excluded. The shared projection retains a safe fallback for manually attributed historical data; the frontend's unavailable-details presentation is tested. |

## 7. Where tests run (local → PR gate → after packaging)

1. **Your machine.** Backend: `./mvnw clean verify` (needs Postgres). Frontend: `npm run lint && npm test && npm run build`. E2E: `npm run test:e2e` (see `frontend/e2e/README.md`).
2. **CI on the pull request** (the required gate): `traceability`, `frontend-build` (lint, tests, coverage, build), `backend-build` (unit, integration, coverage), `audit`.
3. **After packaging:** `package` builds the jar and frontend once; `smoke` and `e2e` test those exact artifacts.

A weekly scheduled run repeats everything on an unchanged `main`, which is where flaky tests show up.

**Continuous delivery vs continuous deployment** is Week 9 exam material, not a project deliverable: the pipeline stops before deployment by design.
