# Safety Officer workflow (SO-C1–SO-C4)

The Safety Officer role uses the `safety_officer` token claim and the
`/safety-officer` console. Development login: `so1` / `123456` (dev profile
only). API authorization is enforced in `SecurityConfig`; hiding a UI route is
not the access-control boundary.

## Event list and details

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/safety/events/planning` | Safety Officer | Events not yet confirmed (Planning, Awaiting Safety Review, Safety Changes Requested, Safety Approved), soonest first |
| `GET` | `/api/safety/events/confirmed` | Safety Officer | All confirmed events, soonest first |
| `GET` | `/api/safety/events/{eventId}` | Safety Officer | Event details for a Planning or Confirmed event |
| `GET` | `/api/events/{eventId}/venue-bookings` | Safety Officer or assigned Coordinator | Booking status and venue arrangement |
| `GET` | `/api/events/{eventId}/equipment-requests` | Safety Officer or assigned Coordinator | Requested equipment and request status |
| `GET` | `/api/equipment/events/{eventId}/reservations` | Safety Officer, Coordinator, or Technical Support | Equipment actually reserved |

The event detail view also loads `/api/venues/{venueId}` for capacity and venue
facilities. A missing venue, booking, equipment request, or registration decision
is displayed as not finalised/not submitted rather than inferred to be ready.
Cancelled and completed events are excluded from the lists and are not available
through the Safety Officer event-detail endpoint.

## Readiness checklist

The standard checklist is shared by every event and cannot be edited per event:

1. Venue booking is approved and suitable
2. Venue capacity and required facilities are verified
3. Accessibility needs are arranged
4. Required equipment is reserved or confirmed unavailable
5. Registration and attendance arrangements are ready
6. Safety, emergency, and on-site operational arrangements are ready

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/safety/events/{eventId}/readiness` | Safety Officer | Read checklist and latest approval state |
| `GET` | `/api/events/{eventId}/readiness` | Safety Officer or assigned Coordinator | Shared read view for the Coordinator |
| `PATCH` | `/api/safety/events/{eventId}/readiness/items/{itemKey}` | Safety Officer | Save `{ "checked": true, "note": "..." }` |

Checklist edits are accepted only while the event is in Planning. Each item keeps
its current state and note, last checker/time, and last editor/time. The Coordinator
can view this state but cannot edit it.

## Safety decision

`POST /api/safety/events/{eventId}/approval` accepts
`{ "decision": "approve" | "request_changes" | "reject", "note": "..." }`.

Since EC08 (D22), the Event Coordinator sends the event for its safety check, and
a decision is accepted only while the event is **Awaiting Safety Review**.

- Approval requires all six items to be checked. A blocked attempt returns HTTP
  422 and names every outstanding item; no event or review status is changed.
- Successful approval records the reviewer/note/time and moves the event to
  **Safety Approved**. The Event Coordinator is notified and then confirms the
  event (`POST /api/events/{eventId}/confirm`, assigned Coordinator only), which
  notifies the Organiser and the staff involved.
- Requesting changes or rejecting needs a note. It records the review outcome
  (`changes_requested` / `rejected`) and sends the event back to the Coordinator
  (**Safety Changes Requested**) with the note as feedback. They make the changes
  and send it again; the Safety Officer then decides again.
- The checklist can be edited in Planning and while awaiting review, not after
  approval.

## Verification

Run the UI suite/build from the repository root:

```powershell
npm --prefix frontend run test
npm --prefix frontend run build
```

Run focused backend coverage:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml test '-Dtest=ReadinessServiceTest,SafetyOfficerAccessTest,AssignedEventsAccessTest'
```
