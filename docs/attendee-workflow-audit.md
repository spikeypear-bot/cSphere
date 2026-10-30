# Attendee workflow audit — 29 October 2026

Audited the implemented attendee home/navigation, browse, event details,
registration, confirmation, owned history, withdrawal and in-app receipt flows.
Scope is AT01–AT04/AT08 and their existing session/notification integration.
AT07/AT09 remain separate stories; no new notification channel or migration was added.

## Issues corrected

| Workflow | Issue | Correction |
|---|---|---|
| Withdrawal recovery | A delayed reconciliation GET could replace a newer Withdrawn result with Confirmed and restore the button. | Reconciliation now uses the same resource reader and ordering as normal GETs and saved mutations. An older result/error cannot replace a newer applied result. |
| Event navigation | Route changes could retain the previous event's details and allow an old registration response to install its reference on another event. | Event-specific components are keyed by event ID; read results and mutation callbacks are scoped to the active resource. New routes show loading until their own data arrives. |
| Event refresh/retry | A successful focus/poll read did not clear an earlier 404 or failure flag. | A successful resource snapshot replaces the previous error state. |
| Registration confirmation | An older background GET could erase a newly saved registration on screen. | Successful POSTs invalidate earlier reads; registration is derived from the current event snapshot rather than a second, conflicting local cache. |
| Lost registration response | A committed registration could be reported as a failed save and invite another POST. A full-event conflict could leave old availability displayed. | A guarded GET reconciles failed responses. An owned saved reference is recovered without another POST; refreshed capacity/lifecycle controls the next action. |
| Slow networking | Starting a new five-second poll invalidated every earlier pending read, potentially leaving browse/history/the bell unloaded indefinitely when each request took longer than five seconds. | Apply completed results in request order. A still-pending later request does not invalidate a useful earlier result; a newer applied result or mutation still blocks stale responses. |
| Receipt read state | An old notification poll could restore unread flags/count after marking a receipt read. | Read/mark-all mutations establish request barriers before and after the write. Older polls cannot undo the local read state; subsequent fresh polls reconcile with the server. |
| Legacy navigation | `/attendee/register` opened an unfinished scaffold. | It redirects to the implemented `/attendee/browse` entry point. Registration remains an action on event details. |

## Shared read contract

`useAttendeeResource` owns request sequence, latest applied snapshot, active
path, normal GETs, safe follow-up GETs, retry and saved-response application.
AT04 continues using its `useRegistrations` alias. Browsing, event details,
history, confirmation and withdrawal reconciliation therefore follow one
ordering rule. Mutation success invalidates reads that began before it.

Ordinary read failures retain the existing explicit error/retry presentation.
A failed GET used only to refresh capacity after a successful save preserves
the saved confirmation. Failure to reconcile an uncertain withdrawal keeps
retry disabled until status is read successfully. Background reads cannot
overwrite a newer accepted snapshot; navigating away prevents old action
callbacks from updating another resource.

The notification hook retains its existing attendee five-second/staff
thirty-second schedules and read endpoints. Its request barriers also preserve
the other roles' notification behaviour.

## Backend review

No additional server defect was identified in the reviewed attendee paths, and
backend application code was unchanged by this audit. Existing filter-chain
roles/token ownership, generic foreign/missing 404s, shared eligibility,
event-before-registration locking, persisted withdrawal date, confirmed-row
capacity calculation, transactional enqueue and retry deduplication remain.
V56/V57 are unchanged.

Attendee event reads still expose only previously confirmed events and approved
venue arrangements, not pending change proposals. Owned history remains separate
from lifecycle: re-confirmation does not withdraw a registration, cancellation
does not mislabel it Withdrawn, and re-registration retains the old record/link.

## Verification

- All **916 frontend tests** passed, including other roles using shared notifications.
- **135 focused attendee/notification/routing tests** passed during the audit;
  the final **28 new/expanded race and workflow cases** were rechecked after cleanup.
- **160 selected backend tests** passed on a fresh PostgreSQL database, including
  registration ownership, eligibility, capacity/concurrency, migration upgrades,
  transaction rollback, delivery failure/retry and notification regressions.
- **Five Chromium checks** passed against the packaged backend and rebuilt frontend:
  light/dark badges, AT01–AT04 history, AT08 withdrawal/re-registration, and the new
  legacy-route/lost-response recovery journey.
- The new browser journey commits real registration/withdrawal requests through
  the backend and drops their responses. It verifies one POST per action, one
  receipt per type, retained reference/history and restored capacity.
- Frontend TypeScript/build, ESLint, story traceability, documentation links and
  whitespace checks pass. The existing bundle-size advisory remains.

Two existing component mocks were aligned with the API contract: failed saves
distinguish GET from POST, and post-registration event reads include the owned
reference/ID/date. Existing behavioural assertions were retained; no test was
weakened or skipped.

Backend and browser tests use separate disposable databases. Reusing a database
with earlier browser registrations caused global-history count/order assertions
to fail; the clean backend run passed without changing those assertions or
removing real history. The local IDE still requires an isolated Maven build
folder to avoid overwriting compiled classes.

This resolves the [original P2 reconciliation finding](at08-implementation-review.md).
The earlier review's remaining coverage note about directly racing event
cancellation/rescheduling against withdrawal still applies; those operations
share the event lock in code, but no new test for those exact interleavings was
added here. CI, teammate review and backlog reconciliation remain integration steps.
