CREATE TABLE event_reassignment_requests (
    reassignment_request_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES events(event_id),
    requester_user_id uuid NOT NULL REFERENCES users(user_id),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    status text NOT NULL CHECK (status IN ('pending', 'approved', 'declined')),
    requested_at timestamptz NOT NULL,
    decided_at timestamptz,
    decision_reason text,
    CHECK (
        (status = 'pending' AND decided_at IS NULL)
        OR (status IN ('approved', 'declined') AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX idx_one_pending_event_reassignment
    ON event_reassignment_requests(event_id)
    WHERE status = 'pending';

CREATE INDEX idx_event_reassignment_pending
    ON event_reassignment_requests(requested_at)
    WHERE status = 'pending';

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_reassignment_requested';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_reassignment_approved';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_reassignment_declined';

ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable',
        'venue_timing_changed', 'venue_timing_conflict', 'venue_hold_expired') AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
    OR (type::text IN ('event_reassignment_requested', 'event_reassignment_approved',
        'event_reassignment_declined') AND event_id IS NOT NULL AND message IS NOT NULL)
);
