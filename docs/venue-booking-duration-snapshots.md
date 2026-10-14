# VS-C2 — Booking-specific setup and turnaround

Each venue has default setup and turnaround durations. Each venue booking has its own start/end window and saved duration snapshot. Coordinators can submit several venues for one event, with independent windows and durations for each. Advertised event times stay unchanged.

## Occupied periods

- Occupied start = booking start minus its saved setup duration.
- Occupied end = booking end plus its saved turnaround duration.
- Durations are whole minutes from 0 to 10080. Overrides require a reason of at most 2000 characters.
- New bookings save explicit start/end times and selected durations under the event/venue locks. Omitted times fall back to event times; omitted durations fall back to venue defaults.
- Legacy rows with nullable V26 start/end fields use the corresponding event time as a fallback. Read models, calendars, approval, conflict scans and timing edits use the same rule.
- Changing venue defaults does not rewrite existing duration snapshots. Touching occupied boundaries are allowed.

## Coordinator workflow

Select one or more venues. Each selected venue has independent Singapore-time start/end inputs, setup/turnaround inputs and an occupied-period preview. Changing a window or duration rechecks that venue's suitability; submission stays disabled until the matching check succeeds and any override reason is supplied. A failed check can be retried.

The venue-options endpoint accepts optional startDatetime, endDatetime, setupDurationMinutes and turnaroundDurationMinutes query parameters. The booking request accepts the same fields plus timingNote. The batch endpoint accepts a bookings array of those requests. Booking responses retain the incoming startDatetime/endDatetime fields and add a timing object containing the requested and occupied windows, captured durations and conflicts.

Server submission and approval independently recheck availability. Checks include approved bookings, recorded unavailability, operational issues and opening hours over the entire occupied period. Batch submission is transactional: a failure rolls back all requests and sends no booking-request notifications. Venue locks are acquired in stable order. The incoming restriction of one active booking per venue per event is retained, even for non-overlapping windows; batch validation identifies buffer-only overlaps before rejecting duplicate venues.

## Staff review and notifications

PUT /api/venue-bookings/{id}/timing is restricted to Venue Staff. It accepts both durations, timingNote, previousSetupDurationMinutes and previousTurnaroundDurationMinutes. Stale durations return 409. Only active pending/approved bookings can be edited. Edits change buffers around the saved booking window, not advertised event times or other bookings.

Expanding occupancy into an approved booking or unavailable period is rejected. Reducing both durations can resolve pre-existing overlaps gradually. The full occupied period must still fit configured opening hours and avoid operational issues. Timing updates notify the assigned coordinator in the same transaction.

GET /api/venues/{id}/timing-conflicts lists approved overlaps. POST /api/venues/{id}/timing-conflicts/scan alerts affected coordinators. Startup scans also identify existing overlaps. Unchanged scans do not duplicate alerts; no booking is automatically removed or cancelled.

Incoming safety-review lifecycle actions, event-confirmation permissions and lifecycle notifications are retained. Venue unavailability still publishes the incoming event that returns affected events to Planning. Equipment loan windows remain independent of venue buffers.

## Migrations after incoming V26

Incoming V1–V26 are unchanged.

- V27__venue_booking_duration_snapshots.sql adds setup_duration_minutes and turnaround_duration_minutes, copies current venue defaults into every existing booking, then enforces non-null/range constraints. Booking windows, statuses and other existing fields are preserved. Zero SQL defaults support legacy inserts; application submissions explicitly capture venue defaults.
- V28__venue_timing_review.sql adds timing_note, timing_updated_at, timing_updated_by, the overlap-notification tracking table, and venue_timing_changed/venue_timing_conflict notification types. Its notification constraint retains all incoming event_confirmed, event_returned_to_planning and event_safety_approved branches.

V27 snapshots current defaults; historical defaults at the original approval date cannot be reconstructed. Effective timestamps remain derived from booking windows and saved durations. The migrations identify neither a new event schedule nor an automatic cancellation policy.

The superseded local V23/V24 timing files are replaced by V27/V28. A database already migrated with those old unpublished local versions needs separate reconciliation before running this branch; do not change applied checksums or rewrite incoming migration files. Verification uses an isolated database and does not migrate development data.

## Verification and fixtures

VenueBookingDurationMigrationTest checks snapshot constraints/backfill and an actual Flyway upgrade from V26 to V28, including pre-existing lifecycle notifications and independent booking windows. VenueTimingWorkflowTest covers multi-venue windows, buffer-only conflicts, batch rollback, opening hours, operational issues, calendar filtering, staff edits, permissions, stale writes and notification rollback/deduplication. Existing approval, unavailability, lifecycle and booking-flow suites provide regression coverage.

Frontend tests cover independent windows and durations in a batch, Singapore-time conversion, changed-window availability checks and staff previews based on booking times rather than event times.

The optional backend/dev/seed/vsc2-seed.sql fixture requires a development backend migrated through V28 and seeded ec1/eo1 accounts. Run with psql -v vsc2_local_dev=on. It preserves existing fixture records on reruns and supplies legacy nullable-window examples via event-time fallback.

Validation on 14 October 2026: the full backend suite ran 947 tests; 946 passed initially and the migration fixture was corrected, after which both migration tests passed and Maven verify packaged the application. All final Surefire reports show zero failures/errors. All 418 frontend tests, ESLint, the production build and diff whitespace checks passed. A live packaged-backend smoke check on an isolated database exercised login, two-venue batch submission, approval and an independent staff timing edit. The temporary server and test database were removed; development data was unchanged.
