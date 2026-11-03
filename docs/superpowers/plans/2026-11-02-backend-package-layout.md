# Backend Package Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the 8 flat backend feature packages into D3's `controller/dto/entity/repository/service` layout and delete the empty `filter/` folder, with no behaviour change.

**Architecture:** A small throwaway Python script does each package's move: `git mv` the files, rewrite `package` lines, rewrite every fully-qualified reference across `backend/src` (imports *and* inline FQNs), and add the explicit imports that same-package access used to make unnecessary. The compiler then says which package-private members must become `public`. One commit per package.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Maven, JUnit 5, JaCoCo; tests run in Docker (no local `javac`).

**Spec:** `docs/superpowers/specs/2026-11-02-backend-package-layout-design.md`

## Global Constraints

- No change to API URLs/JSON, the database schema or Flyway migrations.
- No class renames, splits or logic changes. Only `package`/`import` lines, inline FQNs, and visibility change.
- Visibility is widened to `public` only where the compiler requires it.
- No test assertion changes; no test file loses tests or assertions (`.github/scripts/test-protection.py`).
- `config/`, `common/`, `mock/` and `venuebooking/hold/` are not touched.
- Don't commit regenerated `docs/class-diagrams/*.generated.puml`, `docs/er-diagrams/schema.generated.puml` or `docs/test-traceability.md`.
- Historical docs (`docs/decision-log.md`, remediation notes) keep their old paths; they describe the past.

## Review Focus

1. **Inline fully-qualified names in code** (e.g. `new com.example.connect_sphere.safety.UpdateReadinessItemRequest(...)` in `event/EventLifecycleFlowTest.java:60`, `...venueavailability.AvailabilityModels.Settings` in `changerequest/BookingAssessmentFlowTest.java:70`) must be rewritten, not just `import` lines. → The script rewrites every `old.FQN` occurrence; Task 2 Step 4 greps for leftovers.
2. **JaCoCo's 100% venue-map gate** (`backend/pom.xml:209`, `<include>com/example/connect_sphere/venuemap</include>`) would silently match nothing once `venuemap` has no root classes, so the gate would be lost without failing. → Task 2 changes it to `com/example/connect_sphere/venuemap/*` and checks the report still lists those packages.
3. **Class names that also appear as words** (in comments or strings) could get an unneeded import. → The script only adds an import when the name appears as a whole word outside comment lines; Task 4 compiles with the full suite, and a reviewer scans the diff for imports of classes a file doesn't use.
4. **Same-package access from tests** that stay at the feature root (flow/migration tests) to package-private main code. → Fix by widening the main member, never by moving the assertion or deleting the test.
5. **A stale `target/`** can keep old class files and hide a missed reference. → Always run `clean`.

---

## File placement (decided)

Main code under `backend/src/main/java/com/example/connect_sphere/<pkg>/`:

| Package | controller | dto | entity | repository | service |
|---|---|---|---|---|---|
| `attendee` | AttendeeEventDetailsController, AttendeeRegistrationController | AttendeeEventDetailsDto, AttendeeEventListItemDto, AttendeeRegistrationDetailsDto, AttendeeRegistrationDto | AttendeeRegistration | AttendeeRegistrationRepository | AttendeeEventDetailsService, AttendeeRegistrationQueryService, AttendeeRegistrationService, AttendeeRegistrationException, AttendeeEventStatus, AttendeeWithdrawalPolicy |
| `changerequest` | BookingAssessmentController, ChangeRequestController, VenueRequirementReviewController | ChangeRequestDto | ChangeRequest, ChangeRequestStatus, VenueRequirementReview | ChangeRequestRepository, VenueRequirementReviewRepository | BookingAssessmentService, ChangeImpactService, ChangeRequestService, EquipmentRequestAttentionService, VenueRequirementChangeService, VenueRequirementReviewService, ChangeRequestException, ChangeApproved, ChangeFields |
| `equipment` | EquipmentCatalogueController, EquipmentReservationController, EquipmentStatusController | AddStatusPeriodRequest, EquipmentAvailabilityResponse, EquipmentCatalogueResponse, EquipmentDetailResponse, EquipmentReservationResponse, EquipmentUnitResponse, ReserveBatchRequest, ReserveEquipmentRequest, StatusPeriodResponse, UnitStatusPeriodResponse | Equipment, EquipmentLog, EquipmentStatus, EquipmentStatusPeriod, SerialisedEquipment, SerialisedEquipmentId | EquipmentLogRepository, EquipmentRepository, EquipmentStatusPeriodRepository, SerialisedEquipmentRepository | DevEquipmentSeeder, EquipmentAvailabilityService, EquipmentCatalogueService, EquipmentReservationService, EquipmentStatusService, ReservationLineException |
| `equipmentrequest` | EquipmentRequestController | EquipmentRequestDetailsResponse, EquipmentRequestLineResponse, EquipmentRequestResponse, SubmitEquipmentRequest, UpdateEquipmentRequestStatus | EquipmentRequest, EquipmentRequestLine, EquipmentRequestLineId, EquipmentRequestStatus | EquipmentRequestLineRepository, EquipmentRequestRepository | EquipmentRequestDecisionService, EquipmentRequestQueueService, EquipmentRequestService, EquipmentRequestStateException, InvalidEquipmentRequestException, EquipmentRequestSubmitted |
| `equipmentreview` | EquipmentChangeReviewController | EquipmentChangeReviewDto | EquipmentChangeReview, ReviewOutcome | EquipmentChangeReviewRepository | EquipmentChangeReviewService, TechnicalSupportNotificationService, EquipmentChangeImpact |
| `safety` | EventReadinessController, SafetyOfficerController | ReadinessDto, ReadinessItemDto, SafetyApprovalRequest, SafetyEventListItemDto, UpdateReadinessItemRequest | EventSafetyReview, ReadinessItemState | EventSafetyReviewRepository, ReadinessItemStateRepository | ReadinessService, VenueSafetyChecklist, IncompleteReadinessException, InvalidReadinessUpdateException |
| `venueavailability` | AvailabilityController, TimingConflictController | AvailabilityModels | — | AvailabilityRepository | AvailabilityService, ExistingTimingConflictScan, TimingConflictService, VenueReplanQueue, VenueReplanWorker, VenueArrangementChanged, VenueBecameUnavailable, EffectivePeriod, OperatingHours |
| `venuemap` | VenueMapController | VenueMapDto | — | VenueMapRepository | VenueMapService |

Tests under `backend/src/test/java/com/example/connect_sphere/<pkg>/`, following `event/` (flow tests at the feature root, class unit tests beside their class): a test named `<MainClass>Test` moves to that class's sub-package; every other test (`*FlowTest`, `*MigrationTest`, `*AccessTest`, …) stays at the root. That moves:

- `attendee/service/`: AttendeeEventDetailsServiceTest, AttendeeRegistrationQueryServiceTest, AttendeeRegistrationServiceTest
- `changerequest/service/`: ChangeFieldsTest, ChangeImpactServiceTest, ChangeRequestServiceTest
- `equipment/service/`: DevEquipmentSeederTest, EquipmentAvailabilityServiceTest
- `equipmentrequest/service/`: EquipmentRequestDecisionServiceTest, EquipmentRequestQueueServiceTest, EquipmentRequestServiceTest
- `equipmentreview/service/`: EquipmentChangeImpactTest, EquipmentChangeReviewServiceTest, TechnicalSupportNotificationServiceTest
- `safety/service/`: ReadinessServiceTest
- `venueavailability/service/`: EffectivePeriodTest

---

### Task 1: Worktree and green baseline

**Files:** none changed.

- [ ] **Step 1: Create the worktree** (superpowers:using-git-worktrees) on a new branch `refactor/backend-package-layout` from `main` (`d1a7b03` or later).
- [ ] **Step 2: Run the full backend suite on the untouched branch**, against a throwaway database (see the backend-tests-in-docker memory: `docker run --network csphere_default` with the `backend-test` image, `SPRING_DATASOURCE_URL` pointing at a scratch DB, `-e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1`, and `chown -R` of `target` at the end).
  Run: `./mvnw -B -ntp clean verify`
  Expected: `BUILD SUCCESS`. Record the `Tests run:` totals; Task 4 must match them. If it isn't green, stop and report — don't refactor on a red base.

### Task 2: The move script, proved on `venuemap`; delete `filter/`; fix the JaCoCo gate

**Files:**
- Create (scratchpad, not committed): `move_package.py`
- Move: `venuemap/*` per the table; `test/.../venuemap/VenueMapTest.java` stays at root
- Delete: `backend/src/main/java/com/example/connect_sphere/filter/`
- Modify: `backend/pom.xml:209`

**Interfaces:**
- Produces: `python move_package.py <pkg> <placement.json>` where `placement.json` maps `{"<Class>": "<subfolder>"}` for main classes and `{"test:<TestClass>": "<subfolder>"}` for tests that move. Run from the worktree root.

- [ ] **Step 1: Write `move_package.py`.** For package `p` and each class `C` going to sub-folder `s`:
  1. `git mv` main (and listed test) files into `p/s/`.
  2. Rewrite `package com.example.connect_sphere.p;` → `package com.example.connect_sphere.p.s;` in moved files.
  3. Across every `.java` under `backend/src`, replace `com.example.connect_sphere.p.C` (as a whole word, so `...p.ChangeRequest` doesn't hit `...p.ChangeRequestDto`) with `com.example.connect_sphere.p.s.C`. This covers imports, static imports and inline FQNs.
  4. For every file that is or was in `p` (main and test, any sub-folder or root), add `import com.example.connect_sphere.p.s.C;` for each `C` it references as a whole word on a non-comment line, unless it's in the same new package or already imported. Insert after the `package` line's import block, keeping imports sorted with the existing ones.
  5. Wildcard imports `import com.example.connect_sphere.p.*;` elsewhere: replace with the explicit imports that file needs.
  Self-check at the end: assert no `.java` file remains directly in `p/` (main) and no `com.example.connect_sphere.p.<C>` for a moved `C` remains anywhere.
- [ ] **Step 2: Run it on `venuemap`** with the placement from the table.
- [ ] **Step 3: Delete `filter/`** (`rmdir`; it is empty and untracked by git, so there is nothing to commit for it beyond its absence).
- [ ] **Step 4: Check for leftovers.**
  Run: `grep -rn 'connect_sphere\.venuemap\.Venue' backend/src | grep -vE 'venuemap\.(controller|dto|repository|service)\.'`
  Expected: no output.
- [ ] **Step 5: Fix the JaCoCo gate** at `backend/pom.xml:209`: `<include>com/example/connect_sphere/venuemap/*</include>`.
- [ ] **Step 6: Compile and run the venue-map tests plus the coverage gate.**
  Run: `./mvnw -B -ntp clean verify -Dtest='VenueMap*' -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: `BUILD SUCCESS`, and `target/site/jacoco/jacoco.xml` contains `<package name="com/example/connect_sphere/venuemap/service"` (proves the gate still sees the code). Then set one limit's minimum to `1.1` locally, rerun, expect the `venue-map-coverage` check to fail, and revert that edit — this proves the rule still bites.
- [ ] **Step 7: Commit.**
  ```bash
  git add -A backend/src backend/pom.xml
  git commit -m "refactor(venuemap): layered package layout; keep the 100% coverage gate"
  ```

### Task 3: Move the remaining 7 packages, one commit each

**Interfaces:**
- Consumes: `move_package.py <pkg> <placement.json>` from Task 2.

Order (smallest first, so script surprises show up cheaply): `equipmentreview`, `venueavailability`, `safety`, `attendee`, `equipmentrequest`, `changerequest`, `equipment`. For each `<pkg>`:

- [ ] **Step 1: Run the script** with that package's row of the placement table and its test list.
- [ ] **Step 2: Compile main and tests.**
  Run: `./mvnw -B -ntp clean test-compile`
  Expected: `BUILD SUCCESS`. On "`X` is not public in `...`; cannot be accessed from outside package", add `public` to that type/constructor/method/field only. Repeat until it compiles.
- [ ] **Step 3: Run that package's tests.**
  Run: `./mvnw -B -ntp test -Dtest='com/example/connect_sphere/<pkg>/**/*'`
  Expected: `BUILD SUCCESS`, same test count as the Task 1 baseline for those classes.
- [ ] **Step 4: Check the diff is mechanical.**
  Run: `git diff --cached -M --stat` after `git add -A backend/src`, then `git diff --cached -M | grep -E '^[+-][^+-]' | grep -vE '^[+-](package |import |\s*$)' `
  Expected: only lines that add `public` or rewrite an inline FQN.
- [ ] **Step 5: Commit** `refactor(<pkg>): layered package layout`.

### Task 4: Full verification

**Files:** possibly `AI_Context.md` (only if it names a moved file path).

- [ ] **Step 1: Full suite.**
  Run: `./mvnw -B -ntp clean verify` (same Docker setup as Task 1)
  Expected: `BUILD SUCCESS` with the same `Tests run:` totals as Task 1, and no new failures/skips.
- [ ] **Step 2: Traceability and test protection.**
  Run: `python .github/scripts/traceability.py --check` and `python .github/scripts/test-protection.py` (against `main`)
  Expected: no "Untraced test files"; no "weakened or deleted". Then `git checkout -- docs/test-traceability.md docs/class-diagrams docs/er-diagrams` to drop regenerated files.
- [ ] **Step 3: Layout check.**
  Run: `for d in attendee changerequest equipment equipmentrequest equipmentreview safety venueavailability venuemap; do ls backend/src/main/java/com/example/connect_sphere/$d/*.java 2>/dev/null; done; ls -d backend/src/main/java/com/example/connect_sphere/filter 2>/dev/null`
  Expected: no output.
- [ ] **Step 4: Current-doc paths.**
  Run: `grep -nE 'connect_sphere/(attendee|changerequest|equipment|equipmentrequest|equipmentreview|safety|venueavailability|venuemap)/[A-Z]' AI_Context.md docs/testing-strategy.md`
  Expected: fix any hit to the new path; leave historical docs alone. Commit `docs: point at the new package paths` only if something changed.
- [ ] **Step 5: Push the branch** only when the user says so; no PR without asking.
