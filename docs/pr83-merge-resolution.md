# PR #83 merge reconciliation

Merged `origin/main` at `21c1b8d` into `feat/vs17-venue-staff-notifs` locally.
The clean feature tip `cacff6c` was saved as
`backup/pr83-before-main-20261026`. The local `main` branch was not changed.
No rebase, reset, revert, force-push, PR merge, or push was performed.

## Reconciliation

- Combined change-impact handling, booking attention and replacement eligibility
  with main's event-status helpers and current workflow rules.
- Retained main's safety feedback, flagged checklist items and injected clock,
  alongside per-booking attestations and arrangement fingerprints. Feedback can
  name a current venue checklist item; unrelated booking keys are refused.
- Preserved all notification enum cases and links, including
  `attendee_registration_confirmed` and `equipment_damage_outcome`, while retaining
  VS17 requirement changes/outcomes, suitability, rejection and approval notices.
- Retained `venueName` in creation, editing and DTO mapping while deriving
  operating information from the feature's structured hours. An unchanged
  missing legacy name is not submitted as an empty-name update.
- Main's SecurityConfig rules, including Attendee and EO registration access,
  remain intact; the feature adds its narrowly scoped venue endpoints.
- Retained requirement snapshots/outcomes, immutable assessment/review history,
  attention status, multi-venue requirements/replacements, operating hours,
  transactional notification recovery and independent venue safety evidence.
- Combined test assertions and story tags, reconciled documentation and regenerated
  generated diagrams/traceability through their existing generators.

## Migration mapping

Main's V1–V41 SQL files are unchanged. Feature migrations retain their dependency
order and move as follows:

| Feature version | New version | Purpose |
| --- | --- | --- |
| V34 | V42 | Venue requirement reviews |
| V35 | V43 | Review outcomes |
| V36 | V44 | Booking attention status |
| V37 | V45 | Assessment history |
| V38 | V46 | Previous assessment snapshot |
| V39 | V47 | Derived operating information |
| V40 | V48 | Workflow recovery and notification outbox |
| V41 | V49 | Unavailability release reason |
| V42 | V50 | Multiple-venue arrangements and safety evidence |
| V43 | V51 | Booking approval notifications |

V42 and V43 extend the database's complete existing notification shape expression
instead of redeclaring an older copy. Later feature extensions retain that
expression, preserving main's attendee and equipment cases. V50 extends the
safety-feedback constraint to accept well-formed per-booking keys alongside all
six standard keys; the service additionally validates event membership.

## Rollout considerations

This sequence supports a fresh database or a database migrated on main through
V41. A database that already applied the feature's old V34–V43 is a different
history: do not run Flyway repair, rewrite history, or reset shared data to make
it fit. Review its history and arrange a separate data-preserving reconciliation
before deployment. Only an isolated local test database was used here.

The branch already includes broad VS17/multi-venue behavior; the conflict fixes
do not remove those requested features. Human review should focus on migration
rollout, per-venue safety feedback and the existing transactional outbox changes.
Local checks do not substitute for the required remote CI and teammate review.

## Verification (26 October 2026)

- Backend: `mvnw.cmd -B -ntp clean verify`, Java 25, **1,676 tests passed**,
  zero failures/errors/skips; packaged successfully. Ran from an isolated copy
  after the active checkout encountered Windows diagram locks and a conflicting
  compiled test class. Generated diagrams were copied back from the passing run.
- Frontend: locked dependencies installed; ESLint and TypeScript/Vite production
  build passed. **810 tests across 80 files passed** under Node 24 with two workers.
  Node 22 workers stalled locally; no tests were skipped to work around this.
- Browser: **all 19 journeys verified**. The full suite passed 18; the remaining
  venue-presentation test omitted main's required venue name. After supplying
  that field, its targeted rerun passed with every assertion retained.
- Flyway: all 51 migrations validated and migrated on a fresh isolated database.
  The populated-V40 upgrade test migrated through V51, retaining registrations,
  public venue names and valid/invalid attendee/equipment notification checks.
  Subsequent validation passed on the migrated database. Main's 41 migration
  files were separately compared and are unchanged.
- Traceability: 204 test files, 70 stories, zero untraced test files. The report
  still identifies four High-priority stories without tests; no scope was added
  to address those unrelated coverage gaps.
- No unresolved conflict markers, whitespace errors, deleted main files, or
  reduced counts of existing tests/assertions. Main's commit is retained as a
  merge parent. The original feature tip remains available on the backup branch.

The frontend build retains a non-fatal large-bundle warning. Remote CI has not
run for this local merge because nothing has been pushed.
