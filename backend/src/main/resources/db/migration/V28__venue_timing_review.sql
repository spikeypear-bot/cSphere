ALTER TABLE venue_bookings ADD COLUMN timing_note text,
    ADD COLUMN timing_updated_at timestamptz,
    ADD COLUMN timing_updated_by uuid REFERENCES users(user_id);

-- Tracks the last notified version of each active approved overlap. Repeated
-- scans do not send duplicate alerts; resolved overlaps are removed from here only.
CREATE TABLE venue_timing_conflicts (
    first_booking_id uuid REFERENCES venue_bookings(booking_id) ON DELETE CASCADE,
    second_booking_id uuid REFERENCES venue_bookings(booking_id) ON DELETE CASCADE,
    fingerprint text NOT NULL,
    PRIMARY KEY(first_booking_id, second_booking_id),
    CHECK (first_booking_id < second_booking_id)
);
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_timing_changed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_timing_conflict';
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable',
        'venue_timing_changed', 'venue_timing_conflict') AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
);
