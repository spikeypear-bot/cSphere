-- VS17: outcomes are separate from booking status and notification read state.
ALTER TABLE venue_requirement_reviews DROP CONSTRAINT venue_requirement_reviews_status_check;
ALTER TABLE venue_requirement_reviews
    ADD COLUMN reviewed_by uuid REFERENCES users(user_id),
    ADD COLUMN reviewer_name text,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN review_note text CHECK (review_note IS NULL OR length(review_note) <= 2000),
    ADD COLUMN reviewed_checks jsonb,
    ADD CONSTRAINT venue_requirement_review_outcome CHECK (
        (status = 'pending' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
        OR (status IN ('suitable','action_required') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND reviewer_name IS NOT NULL AND reviewed_checks IS NOT NULL
            AND (status <> 'action_required' OR (review_note IS NOT NULL AND length(btrim(review_note)) BETWEEN 1 AND 2000)))
    );
-- Preserve the original snapshot and a decided outcome, like the append-only EO/EC audit.
CREATE FUNCTION preserve_venue_requirement_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Venue requirement review history cannot be deleted'; END IF;
    IF OLD.status <> 'pending' OR
        (to_jsonb(NEW) - ARRAY['status','reviewed_by','reviewer_name','reviewed_at','review_note','reviewed_checks'])
        IS DISTINCT FROM
        (to_jsonb(OLD) - ARRAY['status','reviewed_by','reviewer_name','reviewed_at','review_note','reviewed_checks'])
    THEN RAISE EXCEPTION 'Venue requirement review history cannot be changed'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_preserve_venue_requirement_review BEFORE UPDATE OR DELETE ON venue_requirement_reviews
    FOR EACH ROW EXECUTE FUNCTION preserve_venue_requirement_review();
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_requirement_reviewed';
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text IN ('venue_requirement_changed', 'venue_requirement_reviewed') AND venue_booking_id IS NOT NULL
        AND requirement_review_id IS NOT NULL AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text = 'status_change' AND new_status IS NOT NULL)
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
