-- VS17: independent audit snapshots for each affected booking; no booking status changes.
CREATE TABLE venue_requirement_reviews (
    review_id uuid PRIMARY KEY,
    change_request_id uuid NOT NULL REFERENCES event_change_requests(change_request_id),
    booking_id uuid NOT NULL REFERENCES venue_bookings(booking_id),
    event_id uuid NOT NULL REFERENCES events(event_id),
    venue_id uuid NOT NULL REFERENCES venues(venue_id),
    coordinator_id uuid REFERENCES users(user_id),
    coordinator_name text,
    coordinator_email text,
    event_name text NOT NULL,
    venue_address text NOT NULL,
    approved_at timestamptz NOT NULL,
    approved_by uuid NOT NULL REFERENCES users(user_id),
    reserved_start timestamptz NOT NULL,
    reserved_end timestamptz NOT NULL,
    setup_duration_minutes integer NOT NULL CHECK (setup_duration_minutes BETWEEN 0 AND 10080),
    turnaround_duration_minutes integer NOT NULL CHECK (turnaround_duration_minutes BETWEEN 0 AND 10080),
    previous_requirements jsonb NOT NULL,
    new_requirements jsonb NOT NULL,
    status text NOT NULL DEFAULT 'pending' CHECK (status = 'pending'),
    UNIQUE (change_request_id, booking_id),
    CHECK (reserved_end > reserved_start)
);
CREATE INDEX idx_venue_requirement_reviews_booking ON venue_requirement_reviews(booking_id, approved_at DESC);
ALTER TABLE notifications ADD COLUMN requirement_review_id uuid REFERENCES venue_requirement_reviews(review_id);
CREATE UNIQUE INDEX idx_requirement_review_recipient ON notifications(requirement_review_id, recipient_user_id)
    WHERE requirement_review_id IS NOT NULL;
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_requirement_changed';

-- Extend the complete existing shape, including attendee and equipment outcomes.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO STRICT previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text = ''venue_requirement_changed'' AND venue_booking_id IS NOT NULL AND requirement_review_id IS NOT NULL AND event_id IS NOT NULL AND message IS NOT NULL))';
END $$;
