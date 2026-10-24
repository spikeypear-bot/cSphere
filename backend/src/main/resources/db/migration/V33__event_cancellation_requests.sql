-- EO07: an Event Organiser cancels a request before approval, or asks for an
-- approved event to be cancelled and its assigned Event Coordinator decides.
-- Modelled on V30's event_reassignment_requests. V31 is EC-C3's (tentative
-- hold expiry warnings), built in parallel; the gap is intentional.

CREATE TABLE event_cancellation_requests (
    cancellation_request_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES events(event_id),
    requester_user_id uuid NOT NULL REFERENCES users(user_id),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    status text NOT NULL CHECK (status IN ('pending', 'approved', 'declined')),
    requested_at timestamptz NOT NULL,
    decided_by uuid REFERENCES users(user_id),
    decided_at timestamptz,
    decision_reason text CHECK (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 1 AND 2000),
    CHECK (
        (status = 'pending' AND decided_at IS NULL AND decided_by IS NULL AND decision_reason IS NULL)
        OR (status = 'approved' AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
        OR (status = 'declined' AND decided_at IS NOT NULL AND decided_by IS NOT NULL
            AND decision_reason IS NOT NULL)
    )
);

-- AC6: at most one pending cancellation request per event, even under a race.
CREATE UNIQUE INDEX idx_one_pending_event_cancellation
    ON event_cancellation_requests(event_id)
    WHERE status = 'pending';

CREATE INDEX idx_event_cancellation_by_event
    ON event_cancellation_requests(event_id, requested_at DESC);

-- event_cancellation_requested: to the assigned coordinator (AC7).
-- event_cancellation_approved / _declined: to the organiser (AC10).
-- event_cancelled: to Venue Staff and Technical Support involved (AC10).
-- request_cancelled: to the assigned coordinator when a request under review
-- is cancelled before approval (AC2).
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_cancellation_requested';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_cancellation_approved';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_cancellation_declined';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_cancelled';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'request_cancelled';

-- Recreated from V32's version (which includes EC-C3's 'venue_hold_expiring'
-- from V31 and EO03/EC-NEW3's change-request types) with the EO07 branches added.
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable',
        'venue_timing_changed', 'venue_timing_conflict', 'venue_hold_expired', 'venue_hold_expiring')
        AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
    OR (type::text IN ('event_reassignment_requested', 'event_reassignment_approved',
        'event_reassignment_declined') AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text IN ('event_cancellation_requested', 'event_cancellation_approved',
        'event_cancellation_declined', 'event_cancelled') AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text IN ('change_request_submitted', 'change_request_withdrawn', 'change_request_decided')
        AND event_request_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text = 'request_cancelled' AND event_request_id IS NOT NULL AND message IS NOT NULL)
);
