# AT09 — Attendee event-change and cancellation notices

AT09 uses the authenticated in-app notification list and the existing
transactional notification outbox. Change notices are queued only after an
Event Coordinator approves a change request and commits it. One notice is
created per active, identified attendee; all relevant field comparisons from
that approval are stored together on the notice. The stored comparison and
cancellation times are snapshots, not joins to the event's later values.

An eligible registration has `status = confirmed` and an `attendee_id`.
Withdrawn, event-cancelled, legacy unidentified and absent registrations do
not receive a notice. The notification API continues to scope list/read
operations to the signed-in user's ID. A change points to the current attendee
event details; a cancellation retains the event's original start and end,
the cancellation reason when supplied, and the cancellation time. The
attendee's registration read model reports `event_cancelled` for a confirmed
registration on a cancelled event, and capacity reads exclude it.

Important fields currently represented in the approved change-request model
are start/end, expected attendance, venue requirements, equipment requirements,
accessibility needs, required facilities and attendee instructions. The current
change-request schema does not record event format or a selected venue/location
as proposed fields, so it cannot produce before/after comparisons for those
values. Those fields need an approved event-model/change-request enhancement
before AT09 can notify about them.

Delivery is in-app only, as confirmed for this project. The outbox records the
notification intent in the same transaction as the event change/cancellation,
attempts delivery after commit, and retries pending work on the existing
30-second schedule. Individual post-commit delivery failures are logged and do
not block another recipient's delivery or roll back the committed event action.
The 30-second schedule is the existing retry cadence, not a separately agreed
delivery SLA. Email delivery is not configured.

The notification bell retains its existing load-error and retry behavior.
Opening/reading a notice updates notification read state only; it does not
change a registration.
