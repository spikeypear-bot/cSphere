# Event cancellation API (EO07)

An Event Organiser can stop ConnectSphere arranging an event that will no
longer take place. How depends on how far the request has got (decision-log
D27):

- **Before approval** (Submitted, Under Review, Clarification Required) the
  organiser cancels the request itself, at once.
- **After approval** (Planning, Awaiting Safety Review, Safety Changes
  Requested, Safety Approved, Confirmed) the organiser *requests*
  cancellation. The event keeps its status and arrangements until the event's
  **currently assigned** Event Coordinator approves or declines. Approving
  cancels the event.

| Method | Path | Role | Result |
|---|---|---|---|
| `POST` | `/api/event-requests/{requestId}/cancel` | Event Organiser of the owning organisation | The request becomes Cancelled at once |
| `POST` | `/api/event-requests/{requestId}/cancellation-request` | Event Organiser of the owning organisation | Creates a pending cancellation request for the request's event |
| `GET` | `/api/events/{eventId}/cancellation-requests` | Owning organisation's organisers, the assigned coordinator, the Lead | The event's cancellation requests, newest first (a pending one leads) |
| `POST` | `/api/events/{eventId}/cancellation-requests/{id}/approve` | The event's assigned Event Coordinator | Approves: the event is cancelled |
| `POST` | `/api/events/{eventId}/cancellation-requests/{id}/decline` | The event's assigned Event Coordinator | Declines with a reason: nothing else changes |

Every body is `{ "reason": "..." }`. The reason is trimmed and must be 1–2,000
characters for cancelling a request, requesting cancellation and declining;
for approving it is an optional note (at most 2,000 characters).

Response (`GET`, and the result of the last three calls):

```json
{
  "cancellationRequestId": "uuid",
  "eventId": "uuid",
  "eventName": "Town Hall",
  "requesterName": "eo1",
  "reason": "Our keynote speaker has withdrawn.",
  "status": "pending",
  "requestedAt": "2026-10-20T10:30:00+08:00",
  "decidedByName": null,
  "decidedAt": null,
  "decisionReason": null
}
```

`/cancel` returns the updated event request (`EventRequestDto`).

## Rules

- **Who.** Organiser endpoints are scoped by the token's `organisation` claim:
  another organisation's request or event is reported as not found (404).
  Coordinator decisions check the event's current `coordinator_id` **first**,
  before the request or input is looked at (D25): anyone else gets 403 with
  the plain "not assigned" message. The Lead reads but cannot decide.
- **One at a time.** At most one pending cancellation request per event (also
  enforced by a partial unique index). A second is 409 while one waits.
- **Current state.** Every step takes the event's row lock and re-checks: a
  request already decided, an event already cancelled or completed, or a
  coordinator no longer assigned is refused and nothing changes (409/403).
  After a reassignment the pending request stays with the event, so the new
  coordinator decides it.
- **Approving** (`EventLifecycleService.cancel`, the only place an event
  becomes `cancelled`): the event's `pending` and `approved` venue bookings
  become `cancelled` and their active holds are released; rejected, changed
  or already cancelled bookings are history and stay as they are. Equipment
  reservations are kept as history; equipment availability ignores those of
  cancelled or completed events. The event row is kept.
- **Final.** A cancelled event refuses further planning, venue booking,
  equipment requests, Technical Support review or reservation, safety review,
  confirmation, reassignment and cancellation. A cancelled request can no
  longer be edited, assigned, approved or rejected, and it leaves the Lead's
  unassigned queue and the coordinator's review queue. A cancelled event
  leaves My events, the Lead's overview and the Safety Officer's lists.
- **Timeline.** Each step is a Major entry on the event's change log, with
  who, when and the reason: `request_cancelled`,
  `event_cancellation_requested`, `event_cancellation_declined` and
  `event_cancelled` (from the event's status to `CANCELLED`).
- **Notifications** are sent only after the change commits, so a failed
  action tells no one:

  | When | Who | Type |
  |---|---|---|
  | A request under review is cancelled | Its assigned coordinator, if any | `request_cancelled` |
  | Cancellation is requested | The event's assigned coordinator | `event_cancellation_requested` |
  | Approved | The organiser who asked | `event_cancellation_approved` |
  | Declined | The organiser who asked, with the reason | `event_cancellation_declined` |
  | Approved, and the event had pending or approved venue bookings | Venue Staff | `event_cancelled` |
  | Approved, and the event had approved equipment | Technical Support | `event_cancelled` |

  Booking and equipment approvers are not recorded, so the whole team of
  each role is told, as for confirmation (D22).

## Errors

| Status | When |
|---|---|
| `403` | Wrong role (filter chain), or a coordinator who is not the event's assigned coordinator |
| `404` | Unknown request, event or cancellation request; another organisation's; a cancellation request of a different event |
| `409` | Wrong status (Draft, Rejected, Cancelled, Completed, or approved/not yet approved for the other endpoint), one already pending, already decided |
| `422` | Missing, blank or over-long reason |

Automated evidence: `EventCancellationServiceTest`,
`EventLifecycleCancellationTest`, `EventCancellationNotificationTest` (unit);
`EventCancellationFlowTest`, `CancelledEventEquipmentAvailabilityTest`,
`CoordinatorAssignmentScopeTest`, `NewEndpointRoleAccessTest` (integration);
`CancelRequest.test.tsx`, `EventCancellation.test.tsx`,
`NotificationBell.test.tsx` (component); and the EO07 journey in
`frontend/e2e/failure-paths.spec.ts`.
