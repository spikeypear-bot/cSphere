-- TS11: Technical Support are told when a coordinator submits an equipment
-- request (AC1) and when an approved event change affects an event's
-- equipment (AC4), and record a review of each such change (AC11-AC14).
-- Renumbered from V36 at the merge of main (V34-V36 there); V37-V40 are
-- TS09's (already merged). chk_notification_shape is recreated here from
-- V38's version (TS09's), keeping its equipment_damage_outcome branch and
-- adding TS11's two. Decision-log D34.

-- One row per approved change that affected an event's equipment. The values
-- are kept here, not read back from the event, so a later change does not
-- overwrite this comparison (AC9).
CREATE TABLE equipment_change_reviews (
    review_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES events(event_id),
    change_request_id uuid NOT NULL REFERENCES event_change_requests(change_request_id),
    -- The event's coordinator when the change was approved (AC8).
    coordinator_id uuid REFERENCES users(user_id),
    changed_at timestamptz NOT NULL,
    -- startDatetime, endDatetime and equipmentRequirements, before and after.
    previous_values jsonb NOT NULL,
    updated_values jsonb NOT NULL,
    -- The reservations that no longer cover the event, as they were then.
    affected_reservations jsonb NOT NULL,
    requirements_changed boolean NOT NULL,
    outcome text CHECK (outcome IN ('still_suitable', 'needs_coordinator_action')),
    outcome_note text CHECK (outcome_note IS NULL OR length(btrim(outcome_note)) BETWEEN 1 AND 2000),
    reviewed_by uuid REFERENCES users(user_id),
    reviewed_at timestamptz,
    CHECK (
        (outcome IS NULL AND reviewed_by IS NULL AND reviewed_at IS NULL AND outcome_note IS NULL)
        OR (outcome = 'still_suitable' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (outcome = 'needs_coordinator_action' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND outcome_note IS NOT NULL)
    )
);

-- AC15: processing the same approved change again creates no second review.
CREATE UNIQUE INDEX idx_one_equipment_review_per_change ON equipment_change_reviews(change_request_id);
CREATE INDEX idx_equipment_reviews_by_event ON equipment_change_reviews(event_id, changed_at DESC);

ALTER TABLE notifications ADD COLUMN equipment_request_id uuid REFERENCES equipment_requests(request_id);
ALTER TABLE notifications ADD COLUMN equipment_review_id uuid REFERENCES equipment_change_reviews(review_id);

-- AC15: each Technical Support user is told once per request and once per
-- review. Only the two TS11 types set these columns, so the predicate does not
-- need the new enum values (which cannot be used in the transaction that adds them).
CREATE UNIQUE INDEX idx_one_equipment_request_notice
    ON notifications(recipient_user_id, equipment_request_id) WHERE equipment_request_id IS NOT NULL;
CREATE UNIQUE INDEX idx_one_equipment_review_notice
    ON notifications(recipient_user_id, equipment_review_id) WHERE equipment_review_id IS NOT NULL;

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'equipment_request_submitted';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'equipment_change_review';

-- Recreated from V38's version (V34-V37, V39-V40 do not change it) with the TS11 branches added.
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
    OR (type::text = 'equipment_damage_outcome' AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text = 'equipment_request_submitted' AND event_id IS NOT NULL AND equipment_request_id IS NOT NULL
        AND message IS NOT NULL)
    OR (type::text = 'equipment_change_review' AND event_id IS NOT NULL AND equipment_review_id IS NOT NULL
        AND message IS NOT NULL)
);
