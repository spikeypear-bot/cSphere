# Attendee event and registration badges

The attendee UI displays exactly two badges per event card/details section,
using the shared `AttendeeStatusBadges` component. Event status comes first;
personal registration status or availability comes second. Inline AT03
confirmation details reuse the event page's badges rather than rendering a
second pair. AT04 registration details and history cards use the same component.
This is a display change, not a new registration approval or withdrawal flow.

## Event badge

| Attendee API value | Label | Tone |
|---|---|---|
| `confirmed` | Confirmed | Green |
| `reconfirming` | Awaiting Re-confirmation | Amber |
| `cancelled` | Cancelled | Red |
| `completed` | Past Event | Grey |

Backend lifecycle values and the canonical lifecycle resolver are unchanged.
Previously confirmed events returned to planning/safety review still map to
`reconfirming`; initial never-confirmed requests remain outside the catalogue.

## Registration/availability priority

The display mapping consumes backend data. For an existing registration:

1. A withdrawn record shows **Withdrawn** (grey), including after later event
   cancellation/completion.
2. A non-withdrawn registration affected by event cancellation, or a stored
   `event_cancelled` record, shows **Affected by Cancellation** (red).
3. A stored confirmed registration shows **Registration Confirmed** (green),
   even when the event is full, registration has closed, arrangements await
   re-confirmation, or the event has finished. The event badge remains separate.

Without a registration, apply the following order:

| Condition | Label | Tone |
|---|---|---|
| Event cancelled | Registration Unavailable | Grey |
| Event completed/past | Registration Closed | Grey |
| Event re-confirming | Registration Paused | Amber |
| Registration not required | Registration Not Required | Grey |
| Before opening instant | Not Yet Open | Blue |
| At/after closing instant | Registration Closed | Grey |
| No remaining places | Full | Red |
| Open with places remaining | Open for Registration | Green |

Missing/invalid registration settings or unknown remaining capacity show
**Registration Unavailable**, rather than implying registration is possible.
Dates are compared as instants; attendee-facing date/time text uses Singapore
time. Registration opens inclusively and closes at event start exclusively.

An active confirmed registration takes precedence over older withdrawn history
on browse/event pages. If no active registration exists, the latest owned record
supplies personal status. Each AT04 history card continues to describe its own
record, so an earlier withdrawn registration remains Withdrawn after a later
successful re-registration. Re-registration eligibility is independent of the
historical badge and is still enforced by AT01 on the backend.

## Read-only API additions

`GET /api/attendee/events` now derives identity from the authenticated token and
adds `registrationOpensAt`, `registrationClosesAt`, `registrationCapacity`,
`availablePlaces` and nullable `registrationStatus` to its existing summaries.
The capacity calculation reuses the same existing confirmed-count query as
event details; ownerless historical registrations continue to consume capacity.

`GET /api/attendee/events/{id}` adds nullable `registrationStatus`, including
withdrawn history. Existing active-only registration reference, ID and timestamp
fields keep their meaning. Neither endpoint exposes another attendee's status
or infers personal status from capacity. AT04 response fields are unchanged.

No migration, enum change, approval workflow or AT01 mutation-rule change is
needed. New reads never create registrations or notifications. Browse and event
details now use the existing focus/five-second refresh pattern, matching AT04.

## Styling and verification

Both badges use the same sizing, spacing and textual labels. Green/red/amber
reuse the existing theme palette; the amber uses the darker booking-hold tokens
for readable light-mode contrast. Blue and neutral grey have explicit light/dark
colours scoped to these badges. Status meaning is always present in text.

Registration/availability uses the reusable UI `OutlinedStatusBadge` component:
its background uses the status colour at 5% opacity and its outline matches the text.
Event status retains its filled style. The same pair is reused across browse,
event details, registration history and saved confirmation details.

- `AttendeeStatusBadges.test.tsx`: priority matrix, opening/closing boundaries,
  confirmed/withdrawn/cancelled combinations, ordering and exact badge count.
- `AttendeeBadgeDataFlowTest`: ownership-scoped browse/detail data, active-over-
  withdrawn priority, full-event capacity, withdrawn history and no writes.
- `attendee-badges.spec.ts`: all four attendee surfaces render exactly two
  badges; all five palette tones have text contrast of at least 4.5:1 in both
  light and dark browser modes.
- Existing AT01–AT04 tests retain their business assertions, with updated labels
  and complete registration-settings fixtures. Periodic refresh tests assert
  the required read occurs rather than depending on which request runs last.

Verification completed on 29 October 2026: all 877 frontend tests, 77 relevant
backend tests and three browser checks passed. Frontend lint, TypeScript and
production build, backend verification, documentation links and test-story
traceability checks passed. The frontend build retains its bundle-size advisory;
the existing traceability report identifies three other High-priority stories
without tests, with no untraced test files.

The outlined registration badge follow-up passed 61 attendee frontend tests,
lint and the production build, plus both light/dark browser checks. The browser
checks now verify faint tinted backgrounds and matching solid outlines on every
attendee surface, and measure contrast against the actual containing surface.
