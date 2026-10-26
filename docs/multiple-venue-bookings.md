# Multiple venue arrangements — 26 October 2026

Implemented against the requirements supplied in this conversation; the live backlog was unavailable. Existing Venue Staff access remains shared. No staff ownership is introduced.

## Decisions and behaviour

- Every submission entry has its own venue, window, duration snapshots, status and optional `requirements` object (`expectedAttendance`, `requiredFacilities`, `accessibilityNeeds`, `venueRequirements`). Omitted/null fields inherit current event requirements; explicit empty lists override them. This permits smaller breakout rooms without changing event attendance.
- Batch submissions retain the existing transaction and sorted venue locking. A failed entry rolls back all entries, requirement groups, history and notification work. Additional venues are allowed while Planning, including after another booking is approved. After safety review/confirmation, a replacement explicitly returns the event to Planning.
- A rejected required venue remains unresolved until an approved replacement or an explicit withdrawal. Replacements share a requirement group; historical terminal records do not independently block readiness. Cancelling an active arrangement withdraws its requirement, except cancelling a pending replacement attempt, which preserves the original requirement. Rebooking a withdrawn arrangement creates a new requirement.
- Healthy approved bookings can be replaced, including rescheduling at the same venue. The original remains reserved until replacement approval. Approval validates current conflicts and suitability before transaction commit; failures preserve the original and unrelated bookings. This supersedes the earlier different-venue-only restriction.
- Safety has one additional checklist item per current booking. Its attestation is tied to the saved arrangement, event requirements, venue catalogue and opening hours. Changes invalidate the relevant attestation; unrelated booking attestations remain valid. Safety approval rechecks live arrangement readiness, and confirmation checks current venue evidence under venue locks.
- Unavailability continues to use live impact flags; an approved affected booking retains its status and reservation. `attention` continues to represent suitability reassessment. Both block readiness without cancelling unrelated arrangements.
- Rejection retains its reason and queues a booking-specific notice for the currently assigned EC. Existing event cancellation continues to release all active event reservations while retaining booking records.
- SO lists and details consider all active venues. EO arrangements include booking identifiers and independent windows. VS details display room requirements. Legacy `events.venue_id` is not the authoritative venue collection.

## API additions

| Endpoint | Access and purpose |
| --- | --- |
| `GET /api/events/{eventId}/venue-options` | Assigned EC; optional room requirement query fields for preview |
| `POST /api/events/{eventId}/venue-bookings/{bookingId}/withdraw-requirement` | Assigned EC; rejected/cancelled booking, no active group member; nonblank `reason`, maximum 2000 characters |
| `GET /api/events/{eventId}/venue-bookings/{bookingId}/history` | Assigned EC; booking must belong to event |
| `GET /api/venue-bookings/{bookingId}/history` | All Venue Staff; other roles forbidden |

History contains chronological before/after booking JSON, event requirement snapshots, timestamp and actor when supplied by the calling workflow. There is no history mutation endpoint. Records begin with V50; pre-migration changes cannot be reconstructed. Normal cancellation and replacement do not delete records.

## Persistence and rollout

V50 adds `venue_booking_requirements`, booking requirements/group linkage, `venue_booking_history` with an insert/update trigger, readiness fingerprints and the rejection notification type. Active legacy replacement chains share a group; terminal-only legacy chains remain historical. Existing safety decisions need current per-booking attestations before reconfirmation. Apply this new migration through the normal reviewed deployment; never modify an applied migration.

## Regression evidence

`MultipleVenueRequirementsTest` covers independent room capacity and approval, batch rollback including side effects, rejected requirements and withdrawal, linked replacement, same-venue rescheduling, independent safety evidence, live safety validation, current-coordinator rejection notice, history access, failed replacement preservation, isolated cancellation/rebooking, preview overrides and the complete multi-venue safety journey.

Existing suites cover concurrent approval, holds and buffered boundaries, unavailability, event cancellation, assignment and role scope. Fixtures now supply the new dependency and complete the actual safety checklist; prior same-venue rejection expectations were updated only for the user's explicit policy decision.

Execution logs are retained under `logs/vs-review-20261025/`. The backend is tested in an isolated build directory and PostgreSQL database; no production data is used. Backlog synchronisation and production deployment are not part of this local change.

### Final local checks

- Full backend run: 1,411 tests, initially two fixture failures (one missing lifecycle stub; one SQL assertion before JPA flush). After correcting the fixtures, both affected classes passed: 22 tests, including all 13 new multi-venue regressions. The full suite was not repeated after these fixture-only corrections.
- Frontend: all 685 tests in 65 files passed; production build and ESLint passed. Vite retains its bundle-size advisory.
- Traceability: 167 files, 64 stories, zero untraced files. Six High-priority stories elsewhere remain without tests.
- `git diff --check` passed. No commit, push or production migration was performed.
- Browser E2E was not run during this verification. Migration V50 was exercised against the isolated PostgreSQL test database.