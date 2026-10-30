# AT04 — Manual workflow and verification

Reviewed against the implementation on 29 October 2026. This guide describes
the local implementation, not a declaration that the story has been reviewed,
merged, or marked Done in the live backlog.

## Start here

Run the local development backend/database and frontend as described in the
root README. The usual frontend address is `http://localhost:5173`. In the dev
profile, use `att1` through `att5`, password `123456`. These are local demo
accounts; do not use them outside development.

Open **Browse events** → an event → **Register for this event**. Then open
**My Registrations** → **View registration** → **View latest event details**.
The implemented registration action is on event details; the standalone
`/attendee/register` route remains a placeholder.

## Current local demo batch

The batch labelled `[DEMO AT04 20261028-231329]` was created with ten status
scenarios on 28 October. At the user's request on 29 October, **all ten were
changed to Confirmed, future-dated events with registration open and spare
capacity**. Their names and demo venue booking windows were updated accordingly.
Existing registration references, owners, timestamps and statuses were retained.

- Use `att3` for fresh registration on all ten events, provided you have not
  already registered with that account since the batch was updated.
- `att1` and `att2` already own some registrations. An existing confirmed
  registration shows its confirmation rather than a new registration button.
- `att1` has a withdrawn history row for **Registration history and
  re-registration**. Registering again should retain that row and add a new
  confirmed record with its own reference.
- The batch no longer supplies Cancelled, Completed, awaiting re-confirmation,
  full, not-yet-open or started-event fixtures. Do not expect those labels from
  its original scenario names or this chat's earlier seed description.

These fixtures exist only in the current local database; they are not startup
data or migrations. The ignored `logs/at04-demo-seed.sql` creates a new batch
with the original status scenarios, and `logs/at04-demo-open-registration.sql`
targets the exact batch above. They are local session artifacts, not tracked
setup dependencies. Do not run them against shared or production data.

## Manual checks

| Scenario | Steps and expected result |
|---|---|
| Successful registration → history | Use an unregistered account on an open event. Register once, note the reference, then open My Registrations. The same reference, timestamp, correct event times and Confirmed registration appear. |
| Own detail and latest event | Open View registration. Check separate Registration status and Event status labels, Singapore date/times and withdrawal explanation. Follow View latest event details to the same event. |
| Read-only behavior | Reopen and refresh list/details. Reference, timestamp and registration count stay unchanged; no additional confirmation notification is sent by these reads. |
| Ownership | As `att2`, open a registration-details URL belonging to `att1`. It shows unavailable, without another attendee's record. Direct API access must return 404 too. |
| Empty history | Use an attendee account with no records. Expect No registrations yet and Browse events navigation. Do not delete registrations merely to create this case. |
| API failure and retry | Make the backend unavailable or use browser request blocking for the registration GET. Expect an error. Restore access and select Try again; expect current data. |
| External status refresh | With an authorised development fixture/status update made separately, return focus to the list/details or wait for the next five-second refresh. The new status appears without navigation. AT08's confirmed withdrawal also updates the saved details immediately. |
| Re-registration history | Register for the event with an existing withdrawn row. Both the withdrawn reference and new confirmed reference remain visible. |

The following require separate fixtures; the current open-registration batch
does not exercise them. Prefer the automated tests for repeatable boundaries.

| Fixture | Expected result |
|---|---|
| Previously confirmed event returned to planning/safety checks | Event Awaiting re-confirmation; existing registration remains Confirmed; new registrations paused. This does not describe initial planning of a never-confirmed EO request. |
| Cancelled event with a stored confirmed registration | Event Cancelled and explicit warning; registration is not falsely shown as Withdrawn. Withdrawal unavailable. |
| Completed event or confirmed event whose end time has passed | Event Completed (Past event in AT02), history retained, withdrawal unavailable. |
| Event at or after its start instant | Withdrawal unavailable, even if event lifecycle still reads Confirmed. |
| Withdrawn registration, including later-cancelled/completed event | Withdrawn remains visible; current event status shown separately. |
| Stored `event_cancelled` registration | Cancelled by event cancellation, with separate current event lifecycle. |
| Full/not-yet-open event | AT01 refuses new registration without creating a row. These are registration-availability conditions, not additional event lifecycle statuses. |

Do not change event/registration rows in a shared database to exercise these
cases. Local fixture updates are test setup, not evidence that AT04 implements
cancellation, re-confirmation or withdrawal commands.

## AT08 withdrawal checks

Use a fresh owned confirmed registration on a future event:

The action is on **My Registrations → View registration**, below Approved venues
and above View latest event details. Withdrawal availability explains a hidden
button. The [implementation review](at08-implementation-review.md) records the
now-fixed reconciliation race; the [workflow audit](attendee-workflow-audit.md)
links the permanent ordering and lost-response regressions.

1. Open details, select Withdraw Registration, then Keep registration or Escape.
   Status, reference, capacity and notification count must remain unchanged.
2. Confirm withdrawal once. The button becomes unavailable, Withdrawn and its
   SGT date appear, and the original reference/registration date remain visible.
3. Open the personal receipt in the bell. It returns to this exact historical
   registration. My Registrations shows Withdrawn; event details show one more
   place. Re-registering creates a new reference while keeping the withdrawn row.
4. Verify cancelled/completed/started events show an explanation without the
   withdrawal button. Re-confirming future events still allow withdrawal, while
   new registration remains paused.
5. Another attendee opening the URL sees an unavailable message with no details
   or withdrawal action. Duplicate/concurrent API calls and delivery failures
   are covered by the PostgreSQL integration tests in the
   [AT08 contract](attendee-withdrawal-api.md).

Focused AT08 checks, with Java 25 and the configured PostgreSQL test database:

```powershell
# From backend/
.\mvnw.cmd '-Dtest=AttendeeWithdrawal*Test,WithdrawalNotificationTest' test

# From frontend/
npm test -- src/features/attendee/Withdrawal.test.tsx --maxWorkers=1
npm run test:e2e -- e2e/attendee-withdrawal.spec.ts
```

The withdrawal tests now contain the delayed-reconciliation regression from the
review, plus the case where an older read fails after a newer successful read.
`AttendeeWorkflowAudit.test.tsx` covers route changes, error recovery, saved
registration ordering, lost responses, full-event conflicts and slow polling.
`AttendeeNotificationRace.test.tsx` covers unread-state ordering and slow reads.

## Automated verification

Run from `frontend/`:

```powershell
npm test -- src/features/attendee/RegistrationPages.test.tsx src/features/attendee/AttendeeEventDetailsPage.test.tsx
npm test -- --maxWorkers=2
npm run lint
npm run build
```

The full suite passed with two workers during implementation; unrestricted
concurrency on this Windows machine caused five unrelated test timeouts. Keep
assertions and timeouts unchanged rather than retrying failures into a pass.

From `backend/`, with JDK 25 and `SPRING_DATASOURCE_URL` pointing to a local test
database, run the focused verification and package build:

```powershell
.\mvnw.cmd -B -ntp '-Dtest=AttendeeRegistrationQueryServiceTest,AttendeeRegistrationQueryFlowTest,AttendeeRegistrationServiceTest,AttendeeEventDetailsServiceTest,AttendeeRegistrationFlowTest,AttendeeRegistrationMigrationTest,EventRegistrationServiceTest' verify
```

The backend flow tests use real PostgreSQL and the seeded development accounts.
Use a test database rather than a database containing manual testing work.
The existing AT01 migration and concurrency tests are included for regression.

With the built frontend preview and packaged backend running (see
[browser test setup](../frontend/e2e/README.md)), run from `frontend/`:

```powershell
npm run test:e2e -- e2e/attendee-registration-status.spec.ts
```

The browser test creates its own uniquely named confirmed event through the
existing API workflow, registers via the UI, opens list/detail/event pages,
checks another attendee receives 404, and saves desktop/mobile screenshots.

From the repository root, run `python .github/scripts/traceability.py --check`.
It regenerates `docs/test-traceability.md`; do not include that generated file
unless requested. Coverage boundaries are recorded in
[testing strategy](testing-strategy.md#stated-coverage-exclusions).
