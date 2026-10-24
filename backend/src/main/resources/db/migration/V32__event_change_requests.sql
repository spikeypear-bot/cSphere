-- EO03 / EC-NEW3: an organiser asks to change a submitted request or an
-- event; its coordinator decides. Kept in its own table rather than as
-- event_requests rows of request_type 'A' (V3's placeholder): a second
-- event_requests row per event would break every "the request behind this
-- event" lookup (findByEventId: change log, timelines, ECL-C7 access) and
-- would surface in the coordinators' review queues as a new request.
CREATE TABLE event_change_requests (
    change_request_id uuid PRIMARY KEY,
    -- The request being changed; its event, once approved, is changed too.
    request_id uuid NOT NULL REFERENCES event_requests(request_id),
    event_id uuid REFERENCES events(event_id),
    requested_by uuid NOT NULL REFERENCES users(user_id),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    -- Every changeable field as it was when the change was asked for, and
    -- as proposed (the same keys), so the comparison survives later edits.
    original_values jsonb NOT NULL,
    proposed_values jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'withdrawn')),
    requested_at timestamptz NOT NULL,
    decided_by uuid REFERENCES users(user_id),
    decided_at timestamptz,
    decision_reason text CHECK (decision_reason IS NULL OR length(decision_reason) <= 2000),
    -- EC-NEW3: the impacts the coordinator confirmed they had considered.
    acknowledged_impacts jsonb,
    CHECK (
        (status = 'pending' AND decided_at IS NULL AND decided_by IS NULL)
        OR (status IN ('approved', 'rejected', 'withdrawn') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    ),
    CHECK (status <> 'rejected' OR length(btrim(decision_reason)) BETWEEN 1 AND 2000)
);

-- One pending change per request, even for two submissions at the same moment.
CREATE UNIQUE INDEX idx_one_pending_change_request
    ON event_change_requests(request_id)
    WHERE status = 'pending';

CREATE INDEX idx_change_requests_by_request ON event_change_requests(request_id, requested_at DESC);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'change_request_submitted';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'change_request_withdrawn';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'change_request_decided';

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
    OR (type::text IN ('change_request_submitted', 'change_request_withdrawn', 'change_request_decided')
        AND event_request_id IS NOT NULL AND message IS NOT NULL)
);

COMMENT ON TABLE event_change_requests IS
    'EO03/EC-NEW3: organiser change requests and the coordinator''s decision. Append-mostly: a decided row is never reopened.';
