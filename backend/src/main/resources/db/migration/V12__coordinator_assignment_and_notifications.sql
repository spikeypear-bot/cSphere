-- EO09 (status-change notifications) / EO19 (coordinator-assignment
-- notifications) need two things the schema doesn't have yet: somewhere to
-- record which Event Coordinator is assigned to a request/event, and a
-- notifications table to record what was told to whom.
--
-- ALTER TYPE ... ADD VALUE cannot be used in the same transaction that then
-- reads/writes the new value (Postgres restriction, same as V5's `none`
-- accessibility value) — this migration only adds it; EventService is the
-- first place that reads/writes it, in a later request.
ALTER TYPE event_status ADD VALUE IF NOT EXISTS 'pending';

CREATE TYPE notification_type AS ENUM ('status_change', 'coordinator_assignment');

-- Coordinator assignment lives on both event_requests (assigned while a
-- request is still under review, before approval) and events (carried over
-- on approval so the same coordinator remains reachable once an event
-- exists) — see EventRequestService.approve(), which copies it across.
ALTER TABLE event_requests
    ADD COLUMN coordinator_id UUID REFERENCES users(user_id),
    ADD COLUMN rejection_reason TEXT;

ALTER TABLE events
    ADD COLUMN coordinator_id UUID REFERENCES users(user_id);

-- One row per notification actually delivered in-app. Deliberately
-- denormalised (event_name, coordinator_name/email are snapshots taken at
-- the moment of the triggering action, not live joins) so a notification
-- still reads correctly even if the event/request/coordinator is later
-- renamed, reassigned, or deleted — matching EO09/EO19's own "does not
-- expose ... unless retained" framing: what's shown is exactly what was true
-- when it was sent, nothing computed after the fact.
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL REFERENCES users(user_id),
    event_request_id UUID REFERENCES event_requests(request_id),
    event_id UUID REFERENCES events(event_id),
    type notification_type NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    -- Text, not a DB enum: this spans two different status vocabularies
    -- (event_request_status's 'pending'/'approved'/'rejected' and
    -- event_status's 'confirmed'), so a shared enum would either duplicate
    -- values or couple two independent status types together for no benefit.
    new_status VARCHAR(50),
    reason TEXT,
    coordinator_name VARCHAR(255),
    coordinator_email VARCHAR(255),
    is_reassignment BOOLEAN,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,

    CONSTRAINT chk_notification_shape CHECK (
        (type = 'status_change' AND new_status IS NOT NULL)
        OR (type = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    )
);

-- Every list/unread-count query is "this recipient's notifications, newest
-- first" or "this recipient's unread ones" — both walk this index in order,
-- no sort step needed.
CREATE INDEX idx_notifications_recipient ON notifications (recipient_user_id, occurred_at DESC);

COMMENT ON COLUMN event_requests.coordinator_id IS
    'EO19: the Event Coordinator assigned to review this request. Null until EventRequestService.assignCoordinator() is called.';
COMMENT ON COLUMN event_requests.rejection_reason IS
    'EO09: recorded by EventRequestService.reject(); shown to the Event Organiser via the rejection notification.';
