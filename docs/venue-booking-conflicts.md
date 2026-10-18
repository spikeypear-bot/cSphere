# VS-C4 — Existing booking conflicts

VS-C4 identifies overlapping saved booking occupancy without changing bookings,
event times, statuses, reservations or durations. It extends the VS-C2 conflict
service, VS15 calendar and VS16 booking details route.

## Rules and agreed scope

- Occupancy expands each booking's own saved window by its saved duration
  snapshots. Nullable legacy booking times fall back to event times. Advertised
  event times are displayed separately. Venue-default changes affect future
  requests only.
- Overlap uses strict bounds: `a.start < b.end && b.start < a.end`.
  Touching boundaries are allowed; midnight and offset timestamps use the same
  rules. Each distinct pair is returned once, including multiple partners for
  one booking. Only bookings of the selected venue are compared.
- The user confirmed that conflict review retains the existing active-event
  scope: approved bookings excluding completed/cancelled events. Pending,
  rejected, changed and cancelled bookings do not enter that list.
- Calendar history retains its existing rules (completed events remain visible;
  cancelled events do not). Historical overlaps in the loaded range can still
  receive calendar indicators. VS08 approval eligibility is unchanged.
- The user confirmed the existing Venue Staff role scope. The schema has no
  staff-to-venue assignment relationship, so all VS accounts can access all
  venues. Per-staff venue ownership remains a documented dependency, rather
  than a new assignment model introduced by this story.

## API and refresh

`GET /api/venues/{venueId}/timing-conflicts` calculates pairs from current stored
data and returns `Cache-Control: no-store`. Each pair has `first`, `second`,
`overlapStart`, and `overlapEnd`. Booking entries retain their previous fields
and add venue ID/name, advertised event start/end, and setup/turnaround minutes.
An existing venue without conflicts returns `[]`. Invalid IDs, missing venues,
authentication and role errors follow existing conventions; database or
transaction failures return 503 with a useful message.

The schedule response also includes `activeConflicts` for the whole venue,
even when `bookings` is filtered by a calendar range. This means a visible
booking is highlighted when its partner is outside the visible dates. A bounded
schedule uses one bounded booking read and one active booking read, not one
query per booking. Both conflict endpoints share the same pair calculation.

Calendar/list data is derived on retrieval, including after changes made by
other workflows. Existing fresh reads on mount, focus and explicit refresh are
retained. Schedule entries, conflict cards and entry dialogs use the same
retrieved response. Dialog selection is stored by entry ID so a refreshed
dialog uses current times and disappears if its entry was released. Successful
booking timing edits update the details summary from the server response;
returning to the calendar or venue details retrieves fresh conflict information.

The pre-existing POST scan/startup scan and `venue_timing_conflicts` fingerprints
are retained for notification deduplication. GET requests neither write those
fingerprints nor send notifications. Viewing the calendar never invokes a scan.

## UI and acceptance coverage

| Requirement | Implementation |
|---|---|
| All affected pairs and multiple partners | Shared `TimingConflictService.overlaps`, unique ID ordering, shared `BookingConflictList` |
| Understand occupied periods | Advertised times, booking window, saved durations, occupied period, exact overlapping interval, venue and status on each pair card |
| Calendar highlighting | Warning inset border and visible/accessible Conflict label on all affected booking segments; existing status colours, tentative entries and unavailability retained |
| Booking navigation | Cards/dialogs link both bookings to `/venue-staff/bookings/:bookingId`; staff timing summary links existing conflict references |
| Resolution and fresh data | Dynamic GET, schedule refresh/focus/remount, server timing-save response; cancelled/released bookings disappear from active results |
| No conflicts, loading and failures | Explicit empty state, loading indicators, retry; failed reads remove stale results and never claim an empty result |
| Read-only review | No new mutation endpoint, status, migration or resolution action; existing approval/timing locks and notification behavior retained |
| Access restrictions | Existing VS role enforcement retained; assignment limitation explicitly agreed above |

## Files

- Backend: `AvailabilityModels`, `AvailabilityRepository`, `AvailabilityService`,
  `TimingConflictService`, `TimingConflictController` extend current read models,
  reuse pair calculation, include full venue conflicts, and handle freshness/errors.
- Frontend: `BookingConflictList.tsx`/`.css` provide shared cards;
  `TimingConflictReview` reuses them and adds a GET-only refresh;
  `VenueSchedulePage.tsx`/`.css` add calendar indicators and dialog references;
  `VenueUnavailabilityPanel` extends schedule types;
  `BookingTimingSummary`/`BookingTimingEditor` enable staff-only detail links.
- Tests: `TimingConflictServiceTest`, `VenueTimingWorkflowTest`,
  `TimingConflictReview.test.tsx`, `VenueSchedulePage.test.tsx` cover boundaries,
  midnight, pairs, read-only retrieval, current booking data, cancellation,
  access/errors, cards, links, refresh and calendar indicators/off-screen partners.
- Documentation: this guide, calendar API guide, README, generated test matrix.

General event amendments remain limited to existing authorized workflows.
VS-C4 adds no event amendment or manual resolution workflow. Venue defaults do
not retroactively create conflicts in saved booking snapshots. Existing timing
edits block expansions that create conflicts and allow reductions of an existing
conflict. Tests use stored legacy overlaps to exercise review and resolution.

## Verification — 17 October 2026

- 117 backend tests passed: `TimingConflictServiceTest`,
  `VenueTimingWorkflowTest`, `VenueBookingApprovalTest`,
  `VenueUnavailabilityTest`, `VenueBookingReadTest`. This includes the off-screen
  partner schedule assertion, role restrictions, 503 retrieval failure,
  cancellation/time/duration freshness and unchanged-record assertions.
- All 469 frontend tests across 48 files passed. Changed-file ESLint and the
  production TypeScript/Vite build passed.
- `traceability.py --check` passed with zero untraced test files;
  `git diff --check` passed.
- A real Edge browser against the new backend verified conflict GET/no-store,
  both cards and saved durations, four warning-marked calendar segments,
  dialog references, booking-detail links, resolution through the existing
  timing editor, fresh conflict removal, and both bookings remaining approved.
  No browser JavaScript errors occurred. Screenshots and run logs are retained
  under ignored `logs/vsc4-*` paths.
- Backend verification and browser fixtures used a separate local PostgreSQL
  database. A copied source tree under ignored logs avoided interference from
  the editor's Java compiler. The development database was not modified.
  Temporary browser fixtures were cleaned up. The complete Playwright suite
  and complete backend suite were not run; the evidence above is the focused
  backend regression run, full frontend suite and live browser smoke check.
