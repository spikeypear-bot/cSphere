-- Hold attempts extend a booking, rather than introducing another booking system.
-- Retain the occupied interval and each attempt after release/expiry/conversion.
CREATE TABLE venue_booking_holds (
    hold_id uuid PRIMARY KEY,
    booking_id uuid NOT NULL REFERENCES venue_bookings(booking_id) ON DELETE CASCADE,
    reserved_start timestamptz NOT NULL,
    reserved_end timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('active','expired','released','converted')),
    created_by uuid NOT NULL REFERENCES users(user_id),
    created_at timestamptz NOT NULL,
    ended_at timestamptz,
    CHECK (reserved_end > reserved_start),
    CHECK (expires_at > created_at),
    CHECK ((status = 'active') = (ended_at IS NULL))
);
CREATE UNIQUE INDEX idx_one_active_hold_per_booking ON venue_booking_holds(booking_id) WHERE status='active';
CREATE INDEX idx_hold_expiry ON venue_booking_holds(expires_at) WHERE status='active';
CREATE INDEX idx_hold_history ON venue_booking_holds(booking_id,created_at);
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_hold_expired';
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable',
        'venue_timing_changed', 'venue_timing_conflict', 'venue_hold_expired') AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
);
