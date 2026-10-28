# AT04 — View My Registration Status

AT04 extends AT01/AT02 with read-only registration history. It reuses
`AttendeeRegistration`, `event_registrations`, the authenticated attendee namespace,
and the canonical `JourneyStatusResolver`. No migration or new dependency is needed.

## Attendee workflow

1. Sign in with an attendee account. Open **Browse events**, then an event's
   details. AT02 exposes events that have been confirmed at least once; an EO's
   initial request still going through EC planning is not yet attendee-facing.
2. Register from the event details page using the existing AT01 action. New
   registration requires a confirmed event, spare capacity, and an open period;
   registration closes at event start. The separate `/attendee/register` route
   is still scaffolding and is not the implemented registration entry point.
3. Open **My Registrations** from attendee navigation, the home page, or event
   details. The list shows event name, reference, event start/end, registration
   time, registration status and event status, including historical records.
4. Select **View registration**. The details page keeps registration and event
   statuses separate, explains cancellation/re-confirmation/completion, and
   reports withdrawal eligibility. Eligibility is informational; there is no
   withdrawal button or command in AT04.
5. Select **View latest event details** to return to the existing AT02 view of
   the current approved event information. Unapproved change proposals are not
   a separate source of attendee details. If attendee-facing details are
   unavailable, the registration page explains this instead of offering the link.

Existing references and registration timestamps belong to individual records.
Re-registering after a historical withdrawal can create a new confirmed record;
the withdrawn record remains visible with its original reference. Viewing either
record does not register again or change capacity.

## API and access

- `GET /api/attendee/registrations`: the signed-in attendee's complete history,
  ordered by registration timestamp descending, then registration ID ascending.
- `GET /api/attendee/registrations/{registrationId}`: one owned registration.

Both routes use the existing `/api/attendee/**` attendee-only rule. Identity comes
from the token subject, never a request parameter. Individual lookup includes
both registration ID and attendee ID; missing and other-owned records return the
same 404. Legacy rows without an attendee ID are not attributed by name or email.
Unauthenticated requests return 401; authenticated non-attendee roles return 403.
Passing another attendee's ID as a query parameter does not select their history.

The response contains `registrationId`, `reference`, `registeredAt`,
`registrationStatus`, `eventId`, `eventName`, `startDatetime`, `endDatetime`,
`eventStatus`, `eventDetailsAvailable`, `withdrawalAvailable`, and
`withdrawalReason`. Personal profile information and internal event fields are
not returned. List responses use the same small projection and load events in bulk.

## Status contract

`registrationStatus` preserves the stored `confirmed`, `withdrawn` or
`event_cancelled` value. `eventStatus` separately reports `confirmed`,
`reconfirming`, `cancelled` or `completed`, using the same attendee lifecycle
mapping as AT02. Completion can be derived on read by the canonical resolver.
Cancellation never changes a registration to withdrawn. Re-confirmation never
withdraws a registration. A withdrawn registration stays visible even when its
event is later cancelled or completed. Existing AT01 and EO10 response contracts
are preserved.

### Backend-to-attendee event mapping

The backend computes the attendee status before returning it to the frontend.
`AttendeeEventStatus` maps the canonical lifecycle resolver's result:

| Stored event state | Attendee API status | My Registrations label |
|---|---|---|
| `confirmed`, with an end time that has not passed | `confirmed` | Confirmed |
| `pending`, `awaiting_safety_review`, `safety_changes_requested`, `safety_approved` | `reconfirming` | Awaiting re-confirmation |
| `cancelled` | `cancelled` | Cancelled |
| `completed`, or `confirmed` with an end time strictly before the server clock | `completed` | Completed |

For the AT02 catalogue/details, the prior-confirmation gate is important: the
four internal planning/safety states mean **re-confirmation of a previously
confirmed event**, not simply a new EO request whose EC has not finished the
initial checks. AT04 reads owned history directly, including records retained
through a return to planning. Completion is derived on read; viewing does not
persist a new event or registration status.

The current UI uses slightly different labels for the same API values:
Browse events shows **Being re-confirmed** and **Past event**; event details
shows **Arrangements being re-confirmed** and **Past event**; My Registrations
shows **Awaiting re-confirmation** and **Completed**. This is wording variation,
not separate lifecycle states.

| Stored registration | Current event | Expected AT04 presentation |
|---|---|---|
| `confirmed` | Confirmed | Registration Confirmed; Event Confirmed |
| `confirmed` | Re-confirming | Registration Confirmed; event warning; no automatic withdrawal |
| `confirmed` | Cancelled | Registration Confirmed as stored history; Event Cancelled and cancellation warning; attendance is no longer confirmed to proceed |
| `confirmed` | Completed | Registration Confirmed as stored history; Event Completed |
| `withdrawn` | Any lifecycle | Registration Withdrawn remains in history; current event status is shown separately |
| `event_cancelled` | Any lifecycle | Registration Cancelled by event cancellation; current event status is shown separately |

AT04 does not rewrite a stored `confirmed` row to `event_cancelled`. Existing
AT01/EO10 responses can derive `event_cancelled` for a cancelled event; that
older response contract is intentionally preserved rather than copied into
AT04's separate registration/event fields.

## AT08 boundary

The user supplied AT08's criteria on 28 October 2026: an active confirmed
registration can be withdrawn before event start, unless its event is Cancelled
or Completed. At the exact start instant withdrawal is unavailable. These rules
do not exclude an event awaiting re-confirmation. AT04 exposes only eligibility
and its explanation. It does not implement withdrawal, confirmation dialogs,
withdrawal timestamps, capacity release, or withdrawal notifications.

Eligibility is false for a withdrawn or `event_cancelled` registration, a
Cancelled/Completed event, or an event that has started. It is true for a
confirmed registration on a future event awaiting re-confirmation: AT08's
supplied criteria do not prohibit that case. New registration remains paused
while the event awaits re-confirmation; permission to withdraw and permission
to register are different rules.

## Pages and refresh

`/attendee/my-registrations` replaces the existing placeholder;
`/attendee/my-registrations/:registrationId` opens a history record. Both present
separate registration and event statuses, date/times in Singapore time, loading
and failure states, and retry. Empty history links to Browse events. The details
page links to the existing AT02 page for current approved event information,
when attendee-facing details are available.

AT04 list/detail reads bypass browser caching and refresh on route entry, window focus and the
existing five-second live-refresh interval. This picks up new AT01 registrations
and externally changed withdrawal statuses without implementing AT08. Obsolete
responses are ignored after route changes, refreshes and unmounts.
An initial request shows loading text; empty lists show **No registrations yet**.
A failed load or refresh shows an error rather than presenting the last result
as current; **Try again** starts a fresh request and loading state. Missing or
other-owned detail records show an unavailable message with navigation back to
the list, without a retry button. A later scheduled refresh can also recover a
temporary failure. This refresh policy applies to AT04, not all AT02 screens.

The query service uses read-only transactions, ordinary repository reads and no
write locks, registration commands, or notification service.

## Verification

`AttendeeRegistrationQueryServiceTest` covers lifecycle combinations, eligibility
boundaries, history, empty results, read failures and the absence of write calls.
`AttendeeRegistrationQueryFlowTest` covers real API ownership/role/authentication,
legacy identity isolation, registration-to-history integration, live status
changes, and unchanged registration/event/notification data after reads.
`RegistrationPages.test.tsx` covers page states, navigation, separated statuses,
refresh after an external withdrawal, and AT01 → AT04 → AT02 navigation.
`attendee-registration-status.spec.ts` runs that journey in a real browser against
the packaged backend and built frontend, verifies another attendee is refused,
and captures desktop/mobile details views. Local verification passed 48 backend
tests (including AT01/AT02/EO10 regressions), all 836 frontend tests with two
workers, the browser journey, frontend lint/build, backend packaging and story
traceability. The initial unrestricted frontend run had five test timeouts;
limiting worker concurrency resolved resource contention without changing tests.

The test results above were recorded during implementation on **28 October
2026**, not rerun during the documentation review on **29 October 2026**.
For reproducible commands and manual scenarios, see the
[AT04 manual testing guide](attendee-registration-status-manual-testing.md).

The live backlog could not be reached during implementation. Scope follows the
user's AT04 requirements and supplied AT08 criteria; no acceptance-criterion
numbers were invented. No AT03, AT08 or AT09 commands are added.
