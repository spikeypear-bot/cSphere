# Event reassignment API (EC-C1)

An Event Coordinator may ask the Event Coordinator Lead to transfer an active
event. The Lead either assigns a different Event Coordinator or declines with
a reason. This is separate from reassignment of an incoming event request
(ELC-C5), which takes place before an event exists.

| Method | Path | Role | Result |
|---|---|---|---|
| `POST` | `/api/events/{eventId}/reassignment-requests` | Assigned Event Coordinator | Creates a pending request |
| `GET` | `/api/events/{eventId}/reassignment-requests` | Assigned Event Coordinator or a past requester for this event | Returns the caller's latest request, or `[]` |
| `GET` | `/api/event-reassignment-requests` | Event Coordinator Lead | Pending requests, oldest first |
| `GET` | `/api/event-reassignment-requests/coordinators` | Event Coordinator Lead | Eligible coordinators for the decision form |
| `POST` | `/api/event-reassignment-requests/{requestId}/approve` | Event Coordinator Lead | Approves and transfers the event |
| `POST` | `/api/event-reassignment-requests/{requestId}/decline` | Event Coordinator Lead | Declines the request |

Request bodies:

```json
{ "reason": "I am no longer available for this event." }
```

```json
{ "coordinatorUserId": "uuid-of-another-event-coordinator" }
```

```json
{ "reason": "Please continue planning this event with the current assignment." }
```

Reasons are trimmed, required, and limited to 2,000 characters. At most one
pending request may exist for an event. Cancelled and completed events cannot
be requested or reassigned. Approval rechecks that the requester remains the
assigned Coordinator and that the destination is a different Event Coordinator.
The event and its originating approved request receive the new assignment in
one transaction; the event's lifecycle status, bookings, safety review, and
planning data are unchanged. A declined request leaves the assignment intact.

An in-app notification goes to the Lead when a request is submitted. The
requester is notified of the Lead's decision; approval also notifies the new
Coordinator and the event Organiser. Notification inserts happen after the
state change commits and do not roll that change back if notification storage
fails. The activity timeline records request, approval, and decline.

Conflicts (including an already-pending request, stale assignment, or repeat
decision) return `409`; an unknown event/request returns `404`; a caller who
does not own the event receives `403`; invalid/missing reasons or an invalid
destination Coordinator return `422`. Role checks are enforced in
`SecurityConfig`, while assignment checks are enforced in the service.

Automated evidence: `EventReassignmentServiceTest`,
`EventReassignmentFlowTest`, and the two frontend reassignment page test files.
