# AT03 — Receive Registration Confirmation

AT03 extends AT01's saved registration and AT04's owned history with a complete,
reopenable confirmation. It uses the existing attendee APIs, notification bell,
transactional outbox and retry worker. It adds no registration table, delivery
channel, withdrawal command or event-change notification.
The confirmation now uses the shared [two-badge display](attendee-status-badges.md).
Inline confirmations do not duplicate the badges already shown in event details.
Registration status text remains separate from the current event lifecycle.

## Attendee workflow

1. Sign in as an attendee, open Browse events, then an eligible event.
2. Select Register for this event. A successful POST returns the saved
   registration ID, reference, status and timestamp. Only then does the page
   show the registration confirmation. Repeated submissions return the same
   active record without sending another confirmation.
3. The confirmation shows event name, reference, separate registration/event
   statuses, registration time, current event start/end, withdrawal eligibility
   text and all currently approved venues. Dates in the confirmation use SGT.
4. View registration confirmation opens `/attendee/my-registrations/{id}`.
   The persistent notification links to that exact route too. My Registrations
   remains another way to reopen the same confirmation after signing in again.
5. View latest event details returns to AT02. AT03 reuses AT02's attendee-safe
   approved-venue API rather than copying booking approval rules. Pending venue
   requests are excluded. Approved venues affected by unavailability are marked;
   cancelled events do not present venue arrangements as confirmed attendance.

After a successful registration POST, the attendee page immediately requests a
server refresh of the existing notification bell and unread count. This shows
the committed confirmation without a reload or the periodic polling delay;
rejected requests do not trigger this refresh. No notification is fabricated
client-side. Delivery outages still use the outbox retry path. The bell retains
automatic polling as a fallback (five seconds for attendees, thirty seconds
for other roles), and also refreshes when the window regains focus or becomes
visible. This discovers later outbox delivery and registrations made in another
tab without a manual reload. Stale in-flight responses are ignored when a newer
refresh has started. AT04 confirmation data
and approved venue reads refresh on focus and its existing five-second interval.

## Current information and ownership

Notification text is a historical snapshot. The linked registration page loads
current data through AT04's ownership-scoped API and AT02's event API. Withdrawal,
cancellation, completion or re-confirmation therefore changes what the old link
shows without changing its registration identity or issuing an AT09 notice.
Withdrawing and registering again creates a different registration; the old
confirmation continues to open the original history record.

A Cancelled event does not falsely mark a stored registration Withdrawn. Awaiting
re-confirmation does not withdraw a registration. The view distinguishes a
historical registration from an active success message. No other attendee's
registration can be read by changing the URL: AT04 returns 404 for missing and
other-owned records, and the existing role rule rejects non-attendees.

`GET /api/attendee/events/{eventId}` now adds nullable `registrationId`, scoped to
the caller's active registration. Its existing fields and the AT01 POST response
are preserved. No new endpoint or change to AT04's response shape is required.

## Reliable confirmation delivery

```mermaid
sequenceDiagram
    participant A as Attendee
    participant R as Registration service
    participant DB as Database
    participant D as Existing notification delivery
    A->>R: Register
    R->>DB: Lock event; check own active registration and eligibility
    R->>DB: Save registration and enqueue confirmation in one transaction
    DB-->>R: Commit both records
    R->>D: Attempt delivery after commit
    alt Delivery succeeds
        D->>DB: Insert notification; mark outbox item delivered
    else Delivery fails
        Note over DB,D: Registration stays saved; work stays queued
        D->>DB: Retry later using the same notification ID
    end
    R-->>A: Saved registration and confirmation link
```

`createAttendeeRegistrationConfirmation` joins the registration transaction.
Its notification UUID is derived from the registration ID and notification type.
The existing outbox ignores a repeated insert of the same notification ID.
Delivery still uses `FOR UPDATE SKIP LOCKED`, `ON CONFLICT (notification_id) DO
NOTHING`, and one transaction for notification insertion and delivery marking.
Same-attendee concurrent registration requests continue to serialize on AT01's
event lock, backed by the existing active-registration uniqueness constraint.

Delivery failures are caught by the outbox and do not turn a committed
registration into an HTTP failure. Durable enqueueing is part of saving the
registration: if that database write fails, the transaction rolls back both
records instead of reporting a successful registration with no retryable work.
Rejected/rolled-back registrations create neither a success notification nor a
pending outbox item.

If the UI cannot load confirmation or venue details after a successful POST,
it explicitly says the registration was saved and offers a read-only retry.
It does not label that case a failed registration or resubmit the POST. Capacity
is refreshed from the server rather than decremented for an idempotent response.

## V54 migration and compatibility

`V54__registration_confirmation_links.sql` adds nullable
`notifications.registration_id` with a registration FK and lookup index. The
outbox carries the same field in its existing JSON payload; its table is reused.

Delivered notifications and queued work are backfilled only when their exact
confirmation message/reference, recipient and event all match a registration.
This includes withdrawn history and avoids selecting a later re-registration
by event alone. Unmatched legacy notices keep the existing event-details link.
Existing notifications, message snapshots, read flags and registration records
are retained. Earlier Flyway migrations are unchanged.

## Verification

- `RegistrationConfirmationFlowTest`: delivery outage/recovery, same-attendee
  concurrency, rollback, rejection, duplicate enqueue and ownership through API.
- `RegistrationConfirmationMigrationTest`: V53 upgrade with delivered/queued
  confirmations, withdrawn versus active references and another attendee's
  unmatched message.
- `RegistrationConfirmationNotificationTest`: stable identities, different
  re-registration identities, direct detail links and legacy fallback.
- `RegistrationConfirmationEventDetailsTest`: two approved venues, pending venue
  exclusion, own registration ID, and cancelled-event venue suppression.
- `RegistrationConfirmation.test.tsx`: complete confirmation, multiple venues,
  saved-but-unavailable retry, rejection, withdrawal/re-confirmation refresh and
  authoritative capacity refresh.
- The existing attendee browser journey now also checks the actual notification
  link, reload and signing in again, preserving its AT01/AT02/AT04 assertions.

Use the backend and frontend commands in the [AT04 manual guide](attendee-registration-status-manual-testing.md),
including the new `RegistrationConfirmation*Test` classes in the backend test
selection. The browser command remains
`npm run test:e2e -- e2e/attendee-registration-status.spec.ts` from `frontend/`.
Tests trace to AT03 without inventing acceptance-criterion numbers.

Local verification on 29 October 2026 passed 120 backend regression tests plus
the separately added approved-venue filtering test (121 total), all 844 frontend
tests using two workers, frontend lint/build, backend packaging, story
traceability and the real browser journey. The browser test waits for completed
sign-in before reopening its confirmation URL; an initial test run navigated
away before login completed. No timeout, assertion or existing test was removed
to resolve that test setup error.

The immediate-bell follow-up was verified with 47 focused attendee/notification
tests, including success, rejected registration and a late stale response, plus
the browser journey without a post-registration reload. Lint, build and story
traceability also passed. Registration success requests a server read; it never
creates a client-only notification or bypasses committed database status.

The live-app follow-up also covers automatic discovery of later delivery with
the attendee five-second poll (48 focused tests passed). The development backend
was rebuilt and V54 applied to the existing local database without resetting its
data. The no-reload notification browser journey passed against the actual
frontend at port 5173, not only the isolated preview. Frontend lint/build and
traceability passed as well.
