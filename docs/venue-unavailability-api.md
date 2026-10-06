# VS01 — Record venue unavailability

Implemented from the supplied VS01 implementation overview and the agreed replacement-booking workflow (6 October 2026).

## User workflow

Venue Details now places **Unavailability** after Associated Bookings and before Operational Issues. Venue Staff enter start/end date and time and a reason. All inputs and displayed times use Singapore time; API timestamps carry an explicit offset and PostgreSQL stores `timestamptz`.

The application previews affected bookings before saving. If any are affected, staff see the event, event times, effective occupied times and booking status. They can return to the form or explicitly confirm. Recording unavailability never deletes or automatically cancels/rejects a booking or event.

Pending and approved bookings for active events are checked. Changed, rejected and cancelled bookings, and cancelled/completed events, are excluded. A pending booking does not reserve a venue, but still needs follow-up if the venue becomes unavailable.

The original booking remains pending/approved with a separate **Alternative arrangements required** indicator. The assigned coordinator receives an in-app notification linking to the event's venue-request screen. They may submit one replacement request, including for an already-confirmed event. Approval of the replacement marks the original booking `changed` in the same transaction. Rejection or withdrawal of the replacement leaves the original intact and permits another attempt. Original pending bookings cannot be cancelled/rejected while a replacement request is pending.

Replacement approval also updates the event's current venue reference. The changed original booking retains the old venue and all original notes for history.

## Availability and setup/turnaround

Effective occupancy is `[event start − setup, event end + turnaround)`. Two intervals overlap only when `firstStart < secondEnd && firstEnd > secondStart`; touching boundaries do not overlap. Both bookings' buffers participate in booking-to-booking checks. An unavailability interval is not itself padded.

The shared availability service is used by coordinator venue options, booking submission, staff approval, and unavailability preview. Approved bookings and unavailable periods block new requests/approvals. Pending requests remain non-reserving.

Venue Details includes setup/turnaround settings in whole minutes, 0–10080, defaulting to zero for existing venues. Settings are locked while a venue has active bookings: changing buffers around existing commitments requires an impact-review workflow beyond VS01. Configure buffers before taking bookings. VS01 does not add editing or deletion of unavailable periods.

Unavailable periods for the same venue cannot overlap. Both preview and creation return `409` for duplicate, contained, enclosing or partially overlapping ranges, comparing complete timestamp instants. Touching boundaries are allowed: if an existing period ends on 20 October at 14:00, another may start at 14:00 or 17:00 that day. Different venues are checked independently. Creation rechecks existing periods while holding the venue lock, so simultaneous submissions and stale previews cannot insert overlapping periods. Existing historical records are preserved.

The Venue Availability Calendar route now displays a chronological agenda of approved bookings, pending requests and unavailable periods. Each has a text label and distinct visual treatment; booking times include buffers. This is an agenda view rather than a month-grid calendar.

## API

These endpoints require Venue Staff (`VS`). There is no venue-owner mapping in the current model, so the existing role-wide staff access model applies. Coordinator access to replacement requests continues to require assignment to the event.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/venues/{id}/unavailability` | Periods ordered by start, then identifier |
| POST | `/api/venues/{id}/unavailability/preview` | Affected bookings and preview token; no writes |
| POST | `/api/venues/{id}/unavailability` | `201` and saved period |
| GET / PUT | `/api/venues/{id}/occupancy-settings` | Read/update setup and turnaround minutes |
| GET | `/api/venues/{id}/schedule` | Settings, active bookings with effective occupancy, unavailable periods |

Preview request:

```json
{
  "startDateTime": "2027-04-01T09:20:00+08:00",
  "endDateTime": "2027-04-01T10:00:00+08:00",
  "reason": "Maintenance"
}
```

Create uses the same fields plus `previewToken` from the preview response and `acknowledgedBookingIds`, an array containing every displayed affected booking ID. For no conflicts, acknowledgement is unnecessary. The token binds the submitted period/reason and current affected-booking details. The server recomputes conflicts while holding the venue lock. Missing acknowledgement or changed booking details produce `409`; the user must review again. A preview token is a concurrency check, not an authorization credential.

Validation rejects missing/malformed timestamps, non-increasing intervals, and blank or over-2000-character trimmed reasons. Domain validation uses `422`; malformed JSON uses the existing request-error handler; missing venues use `404`; insufficient role access uses `403`. Save/check failures retain form values and do not imply success.

Updated date rules (7 October 2026): both dates must be real calendar dates and the end instant must be strictly later than the start. The start's Singapore calendar date must be today or later; this is a date-based restriction, so the current minute is accepted. The end may be on the same day with a later time. Month/year boundaries and valid leap days are supported; impossible dates such as April 31 and February 29 in a non-leap year return `422`. Both preview and final creation enforce these rules. The form shows live errors, sets date-picker minimums, retains invalid selections for correction, and revalidates on final confirmation in case it was left open across midnight. This supersedes the earlier allowance for past dates in this feature; organiser date rules are unchanged.

`unavailabilityValidation.test.ts` and `UnavailabilityValidationTest` cover all 20 requested scenarios with a fixed reference date, plus Singapore-midnight/offset behaviour. HTTP integration tests verify that bypassing the form still rejects past/impossible dates without writing a period.

Replacement request extends the existing operation:

```http
POST /api/events/{eventId}/venue-bookings
```

```json
{
  "venueId": "replacement-venue-uuid",
  "replacesBookingId": "affected-original-booking-uuid",
  "bookingNotes": "Alternative arrangements",
  "suitabilityNote": null
}
```

Both staff and coordinator booking DTOs expose `requiresAlternative` and `replacesBookingId`. Historical impact links are retained after replacement, while `requiresAlternative` becomes false for the changed original.

## Persistence and consistency

Flyway V20 adds:

- `venues.setup_minutes` / `turnaround_minutes`, default 0 and bounded by database checks.
- `venue_unavailability`, with venue FK, timestamps, reason, creator FK and creation timestamp.
- `venue_unavailability_bookings`, retaining affected-booking links without altering original booking content.
- `venue_bookings.replaces_booking_id`, plus a unique partial index allowing only one pending replacement per original.
- `venue_unavailable` notification type and its shape constraint.

The repository uses parameterized JDBC queries under the existing JPA transaction manager. Unavailability, impact links and in-app notification records commit or roll back together. It deliberately does not use the notification service's independent best-effort transaction, because that could lose or misrepresent this required follow-up.

Submission, approval, rejection, cancellation and recording coordinate using venue locks. Booking mutations retain the event-first lock order. Replacement approval locks both venues in UUID order before changing the original. Staff confirmation never trusts the client preview as a final availability check.

Events without an assigned coordinator still have visible affected-booking indicators; no notification recipient is invented. The normal request approval flow assigns a coordinator before creating an event.

## Verification

Overlap validation verified on 7 October 2026: 41 focused backend tests and 32 frontend tests passed. Coverage includes contained/enclosing/duplicate/partial overlaps, touching boundaries, separate venues, multi-day ranges, equivalent timestamp offsets, stale previews and simultaneous overlapping submissions. Form tests verify overlap errors from preview and save retain all input values.

`VenueUnavailabilityTest` covers buffered overlaps and boundaries, status/venue scoping, mandatory and stale confirmation, preserved booking data, notification recipient, failed-save rollback, shortlist/submission/approval blocking, replacements, role/assignment restrictions, settings and concurrent recording/approval.

`VenueUnavailabilityPanel.test.tsx` covers required fields, invalid ordering, return-to-form, explicit acknowledgement, Singapore timestamps, no-conflict saves and failures. Coordinator page tests cover confirmed-event replacement and preventing a second concurrent request. Existing venue, booking, notification and frontend suites provide regression coverage.

Verified locally on 6 October 2026: the full backend suite passed 337 tests; after adding the current-venue update and notification-failure/API checks, the final focused approval/VS01 run passed all 29 tests. The full frontend suite passed 205 tests with two workers (a prior highly parallel run timed out in an unrelated organiser form test). Production frontend build and scoped ESLint passed. Browser verification confirmed the section order, conflict preview and return-to-form against the rebuilt backend without saving changes to the existing demo bookings. V17 applied successfully to local PostgreSQL.

Manual walkthrough:

1. As Venue Staff, open a venue without active bookings. Set setup to 30 and turnaround to 45 minutes.
2. Request and approve an event from 10:00 to 12:00 at that venue.
3. Record maintenance from 09:20 to 09:40. Verify the conflict appears even though the event starts later.
4. Return to the form once and verify nothing was saved; then review and confirm.
5. Verify the original booking is still approved, flagged, and present in Associated Bookings. Check the agenda shows both the booking and maintenance.
6. As its assigned coordinator, open the notification, choose another available venue and submit the replacement. Verify the original remains approved.
7. Approve the replacement as Venue Staff. Verify the original becomes changed, the replacement approved, and original notes/event information remain available.
8. Repeat with replacement rejection/withdrawal and verify the original remains flagged and a retry is possible.

For a container deployment, rebuild the backend image so it includes V20 and the new classes: `docker compose up -d --build backend`. Do not edit applied migrations or remove database volumes.

## Migration numbering after merging main (7 October 2026)

Venue unavailability moved from V17 to V19 without changing its SQL contents, preserving main's V17 required-facilities and V18 operating-hours migrations. Fresh databases apply V17 through V19 normally. Databases already on main need only apply V19.

For the local database that had already applied `V17__venue_unavailability.sql`, the existing successful history entry was renamed to version 19 / `V19__venue_unavailability.sql`, retaining its checksum and all application data. V17 and V18 then require a one-time `SPRING_FLYWAY_OUT_OF_ORDER=true` migration run. Do not use Flyway clean or delete database volumes. Other databases with the old branch migration need the same verified history reconciliation before upgrading; do not rename main's V17 entry.

A subsequent main merge introduced V19 alternative suggestions. Venue unavailability is now V20, again with unchanged SQL. The local history entry was advanced from V19 to V20 before applying the incoming V19 with the same one-time out-of-order migration procedure. Fresh databases apply all migrations normally.
