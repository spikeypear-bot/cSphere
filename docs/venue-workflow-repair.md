# Venue Staff workflow repairs — 25 October 2026

Update: the 26 October [multi-venue policy](multiple-venue-bookings.md) supersedes the different-venue-only replacement restriction below. Healthy approved bookings and same-venue rescheduling are now supported with reservation retention until approval.

The user authorised the audit corrections and unavailable-period release. They explicitly chose to **retain shared Venue Staff access**: individual staff ownership and venue claiming are not part of this change. The live backlog could not be retrieved; story/acceptance updates must be synchronised by the team. Tests use existing story IDs and descriptive cases, without invented acceptance-criterion numbers.

## Behaviour

| Audit finding | Correction |
|---|---|
| Private booking details accessible to unrelated roles | Both staff booking detail and venue booking-list GETs require VS. Assigned EC, ECL and Safety Officer views continue through their existing event-scoped endpoints. EO arrangements retain their restricted DTO. |
| Operational issues race approval/holds | Issue creation holds the same venue row lock used by approval and holds. If an issue wins, a new commitment is refused. If approval wins, the later issue preserves that commitment, identifies its impact and prevents further safety progression. |
| Existing commitments incorrectly remain ready | Timed overlapping issues participate in the live affected-booking calculation. Current assigned coordinators receive notices. Affected safety-stage events return to Planning through the existing lifecycle. Untimed informational issues and touching boundaries do not block. |
| Required replanning lost after period commit | `venue_replan_tasks` is populated in the triggering transaction. Processing starts after commit and retries on a schedule. Each attempt uses its own transaction; task completion, event transition, activity and queued notices commit together. |
| Historical impact incorrectly treated as current | `affected` derives from current saved occupancy against active unavailable periods and timed operational issues. Historical impact links remain unchanged. Resolving a buffer-only overlap clears the live flag. |
| Final-event occupancy inconsistent across search/approval | Cancelled and completed events do not block new commitments. Historical approved rows are retained. A stale pending row belonging to a final event cannot itself be approved/rejected. |
| Same-venue replacement bypass | Every eligible replacement source must use a different venue, checked at submission and approval. A legacy pending replacement using the same venue is refused at approval. |
| No unavailable-period release | VS can release a period with a reason. The row, impact links, actor, timestamp and reason remain stored. Only that period stops blocking. Booking statuses/reservations and event status are not automatically restored. |
| Best-effort notices can be lost | Former after-commit producers now persist notification work in the originating transaction. Delivery attempts run after commit and retry. The stable notification ID and transactional delivery marker prevent duplicates under concurrent/repeated delivery. Existing hold/review/assessment notices retain their atomic contract. |

Active tentative holds still strictly block competing approvals. Saved booking durations remain snapshots; venue-default edits affect future requests. Capacity/facilities remain hard constraints; missing accessibility requires justification and staff judgement, rather than being labelled automatically suitable. This change does not introduce overnight opening schedules.

## Unavailable-period release API and UI

All endpoints retain VS-only role access, shared across staff accounts.

| Method | Path | Result |
|---|---|---|
| GET | `/api/venues/{id}/unavailability` | Active periods only, preserving the existing API shape |
| GET | `/api/venues/{id}/unavailability/history` | Active and released periods, with `releasedAt` and `releaseReason` |
| PATCH | `/api/venues/{id}/unavailability/{periodId}/release` | Released period; body `{ "reason": "Repairs complete" }` |

Release reasons must contain 1–2000 trimmed characters. Unknown/wrong-venue periods return 404; repeated releases return 409; insufficient role returns 403. The venue lock serialises release with conflicting writers. A failed update rolls back the release.

Venue Details offers **Release unavailable period**, an explicit reason/confirmation form, retained input after failure, refreshed availability and a collapsible history. Released periods disappear from active availability/calendar data. Release never restores an old booking status or skips planning/safety steps.

## Recovery and schema

- V40 adds release metadata, `venue_replan_tasks`, and `notification_outbox`. V41 explicitly requires a release reason at the database boundary. Existing migrations are unchanged; both additions preserve application data.
- Replanning runs immediately after commit when possible and otherwise every `venue.recovery.delay-ms` (default 30000).
- Notification delivery runs immediately after commit when possible and otherwise every `notifications.retry-delay-ms` (default 30000).
- Each scheduled pass handles up to 100 pending items. Failures are logged and retained; completed work remains as operational history.
- Workers lock pending work with `FOR UPDATE SKIP LOCKED`. Repeated/concurrent processing cannot complete an item twice. A delayed replanning task rechecks live venue impact before changing event state, so resolved arrangements are not undone.
- No event locks are acquired while the triggering transaction still holds a venue lock. This preserves the established event-first/venue locking discipline and avoids the previously documented inverse-order deadlock.
- Notification payloads are snapshots of the triggering action and its intended recipients, consistent with retained notification history. Requirement-review outcomes and operational-impact notices resolve the current event coordinator at the action.
- Failure to persist required work rolls back the originating action; failure to deliver the saved work does not undo that action. Rollback leaves neither queued work nor a delivered notice.

## Tests

`VenueWorkflowRepairTest` exercises the security, occupancy, replacement, release, issue/safety, lock ordering and durable recovery boundaries on PostgreSQL, including fault-injected event/notification failures. Existing approval and unavailability regressions were reconciled with the authorised final-event and different-venue rules while retaining source-history and unrelated-reservation assertions. `VenueUnavailabilityPanel.test.tsx` covers release validation, confirmation, failures, refresh and history.

## Verification completed

| Check | Result |
|---|---|
| Full backend `verify`, isolated source copy and PostgreSQL database | 1,398 tests passed, zero failures/errors/skips; packaged application and JaCoCo report generated |
| Full frontend suite | 684 tests across 65 files passed; Windows thread workers used after fork-worker stalls |
| Production frontend build and ESLint | Passed; existing bundle-size advisory remains |
| Full Playwright suite against packaged backend and built frontend | All 19 journeys passed, including release, shared staff access, private cross-role reads, cancellation, reassignment, requirement review and tentative holds |
| Traceability | 166 test files; zero untraced files. Seven pre-existing high-priority backlog stories still lack tests; this change does not claim those stories are complete |
| Whitespace and visual review | `git diff --check` passed; release/history screenshots inspected on desktop and 390px mobile without horizontal overflow |

The backend was verified under `logs/vs-review-20261025/backend-verify` because the editor's automatic compiler interfered with a run in the normal output directory. All executable main-source files matched the verified copy. Earlier environment-affected attempts were superseded by the passing runs above. Tests and browser fixtures used separate disposable databases, not the development database.

Evidence logs under ignored `logs/vs-review-20261025/`: `repair-backend-final.log`, `repair-frontend-threads.log`, `repair-build.log`, `repair-lint.log`, and `repair-browser-final.log`. Generated class/ER diagrams and test traceability were refreshed. No live backlog edits, commit, push or deployment are included. The team still needs to synchronise the authorised corrections and execution evidence to the live backlog.
