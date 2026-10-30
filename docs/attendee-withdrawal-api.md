# AT08 — Withdraw Event Registration

An attendee can withdraw an owned confirmed registration before event start,
including while the event awaits re-confirmation. The confirmation dialog,
persisted history and personal withdrawal receipt reuse AT01–AT04. AT07/AT09
remain independent stories and are not implemented by this change.

## Endpoint

`POST /api/attendee/registrations/{registrationId}/withdraw` takes no body.
The existing `/api/attendee/**` security rule requires an authenticated attendee;
the token subject supplies ownership. Client-supplied attendee IDs are ignored.

- `200`: updated AT04 registration details, including `registrationStatus:
  "withdrawn"`, `withdrawnAt`, and `withdrawalAvailable: false`.
- `401`: unauthenticated; `403`: authenticated with another role.
- `404`, `Registration unavailable.`: missing or other-owned registration,
  without revealing its event, owner, reference or state.
- `409`: already withdrawn or ineligible, with the same reason AT04 displays.
- `400`: malformed registration UUID.
- `503`: database/transaction failure, with a safe message asking the attendee
  to refresh status. A failed delivery after commit still returns 200.

`withdrawnAt` is also added to AT04's list/detail responses. It is null before
withdrawal and for legacy withdrawn rows whose date was not recorded. The
frontend type permits omission for compatibility with older API deployments.
The original ID, reference, owner and registration timestamp remain unchanged.

## Eligibility and consistency

`AttendeeWithdrawalPolicy` is shared by reads and the command. Only a stored
confirmed registration is eligible. Cancelled/completed events and events at
or after their start are refused; missing start times fail closed. Previously
confirmed events awaiting re-confirmation remain eligible before their start.
Registration opening/closing settings do not gate withdrawal. The current
registration schema closes registration at event start; no independent earlier
closing field is introduced.

The command resolves the owned event ID with a scalar query, then locks the
event followed by the owned registration. This matches AT01/lifecycle lock
ordering and avoids loading stale registration state before waiting for a lock.
It checks eligibility and time after locking, then saves status and timestamp
in one transaction. Repeated/concurrent calls produce one success and conflicts
for subsequent calls, without overwriting the first withdrawal timestamp.
Capacity remains the existing count of confirmed registrations; no counter or
event lifecycle mutation is introduced.

V56 adds nullable `withdrawn_at timestamptz`, with no fabricated backfill.
Existing migrations are unchanged. V56 was unused across locally available
branches/remotes when implemented; coordinate numbering before integrating
other teammates' migrations.

## Confirmation and refresh

To find the button: **My Registrations → View registration** opens
`/attendee/my-registrations/{registrationId}`. Scroll below **Approved venues**;
**Withdraw Registration** is immediately above **View latest event details**.
The saved-confirmation link on event details also opens this page.

The AT04 details page offers **Withdraw Registration** only when eligible and
otherwise explains why. Its native confirmation dialog shows event, reference
and SGT start time. **Keep registration** or Escape sends no POST. Pending
submission disables both dialog buttons and double submissions. Changing
eligibility disables confirmation; the server rechecks under locks.

Success immediately applies the saved response, showing Withdrawn and its SGT
date while preserving the reference. Legacy unknown dates display Not recorded.
The real notification bell refreshes from the server; attendee history and
availability refresh on navigation, focus, polling and the attendee change
signal. A shared resource reader guards normal and reconciliation reads, saved
mutations and action results for a different route. A lost response is reconciled by reading current state before
allowing another POST; if that read fails, Refresh registration status is
required before retrying.

The [original review finding](at08-implementation-review.md) about a delayed
reconciliation response is fixed. `useAttendeeResource` compares a response with
the latest applied result/mutation, so older data or errors cannot restore an
earlier state. Valid slow reads still populate the page while a later poll is
pending. See the [workflow audit](attendee-workflow-audit.md) for regression evidence.

## Transactional receipt

V57 adds `attendee_registration_withdrawn` and extends the current notification
shape constraint while preserving other stories' shapes. A receipt requires
event, registration and message fields and reuses V55's registration FK.
`NotificationService.createAttendeeWithdrawalConfirmation` requires the caller's
transaction and uses a stable ID derived from its type and registration ID.
It is a self-receipt exemption from actor suppression. Actor identity comes
from the existing notification service, not from the client.

Withdrawal and outbox enqueue commit together. Enqueue/database failure rolls
back status, timestamp and released capacity. Delivery runs after commit;
failure leaves withdrawal saved and the receipt queued. The existing worker
deduplicates retry and lost-acknowledgement delivery without resetting read
status. The link opens `/attendee/my-registrations/{registrationId}` for the
original historical record, even after later re-registration.

AT01 still governs every new registration; releasing a place does not reopen a
re-confirming event or bypass registration periods. No mutable capacity counter,
event lifecycle transition, waitlist or broadcast notification is introduced.
V57 was unused across locally available branches/remotes when assigned;
coordinate migration numbering and preserve these shapes when integrating
AT07/AT09.

## Verification

- `AttendeeWithdrawalServiceTest`: read/command eligibility agreement, exact
  start boundary, re-confirmation, retained history, unknown legacy dates,
  ownership refusal and time re-check after acquiring locks.
- `AttendeeWithdrawalFlowTest`: real filter-chain authentication/roles/ownership,
  committed timestamp and fresh reads, duplicate/concurrent requests, preserved
  event lifecycle, capacity and rollback of the actual command.
- `AttendeeWithdrawalMigrationTest`: populated V55 upgrade, preserved original
  rows and nullable historical timestamps, plus receipt shape validation.
- `WithdrawalNotificationTest`: original reference/date, exact link and stable
  notification identity across duplicate enqueue attempts.
- `Withdrawal.test.tsx`: cancellation/Escape, double clicks, immediate status,
  eligibility, stale state, lost responses, failed reconciliation, old reads and
  a real notification-bell refresh.
- `attendee-withdrawal.spec.ts`: real-browser registration, cancellation of the
  dialog, mobile withdrawal, receipt, history, released capacity, re-registration
  and denied access by another attendee.

Tests use story tags without invented acceptance-criterion numbers.

Completion verification on 29 October 2026 passed:

- 160 selected backend tests, including AT01–AT04, coordinator/organiser
  notification regressions, withdrawal concurrency, enqueue rollback, delivery
  failure/retry, lost delivery acknowledgements and retained re-registration history.
- 102 attendee/notification component tests; the 13 withdrawal cases were checked
  again after tightening the typed fetch mock.
- Four real Chromium tests: light/dark attendee badges, the existing AT01–AT04
  journey, and the complete mobile withdrawal/receipt/history/re-registration journey.
- Maven packaging/coverage generation, frontend TypeScript/production build,
  ESLint, documentation links, generated diagrams and story traceability with
  zero untraced files. The existing frontend bundle-size advisory remains.

The final backend build used an isolated source copy to avoid IDE-written error
stubs in Maven's normal target folder; compiled-source hashes matched the working
checkout. Browser tests used the packaged jar, built frontend and isolated local
PostgreSQL database. The mobile confirmation screenshot was visually inspected.
The only changed earlier assertion replaced AT04's obsolete no-withdrawal-button
expectation with the now-required enabled button; existing behavioural assertions
were retained. No tests were skipped. These are local implementation results;
CI, teammate review and backlog completion remain the team's merge process.

The subsequent [implementation review](at08-implementation-review.md) reproduced
an additional request-ordering failure, which was then corrected in the
[attendee workflow audit](attendee-workflow-audit.md). Its broader verification
supersedes the earlier completion counts for the audited frontend behaviour.
