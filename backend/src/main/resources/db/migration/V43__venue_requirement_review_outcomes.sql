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
-- Extend the complete existing shape, including attendee and equipment outcomes.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO STRICT previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text = ''venue_requirement_reviewed'' AND venue_booking_id IS NOT NULL AND requirement_review_id IS NOT NULL AND event_id IS NOT NULL AND message IS NOT NULL))';
END $$;
