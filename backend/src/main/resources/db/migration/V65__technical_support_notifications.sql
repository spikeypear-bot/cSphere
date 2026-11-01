-- TS11: Technical Support are told when a coordinator submits an equipment
-- request (AC1) and when an approved event change affects an event's
-- equipment (AC4), and record a review of each such change (AC11-AC14).
-- Renumbered at merges of main: first for V34-V36 (D30/SO-C5/
-- SO-C6) and TS09's V37-V40; then again for main's V41-V51 (VS17 and others,
-- merged in after this branch); then to V65 after main's V52-V64. chk_notification_shape is extended from
-- whatever it currently is, following the dynamic-rebuild pattern V45/V50/V51
-- use, rather than re-enumerating every earlier branch by hand. Decision-log D34.

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

-- Extend the current constraint in place (V45/V50/V51's pattern) instead of
-- re-listing every prior branch, so this never risks dropping one.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text = ''equipment_request_submitted'' AND event_id IS NOT NULL AND equipment_request_id IS NOT NULL AND message IS NOT NULL)' ||
 ' OR (type::text = ''equipment_change_review'' AND event_id IS NOT NULL AND equipment_review_id IS NOT NULL AND message IS NOT NULL))';
END $$;
