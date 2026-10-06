ALTER TABLE venues
    ADD COLUMN setup_minutes integer NOT NULL DEFAULT 0 CHECK (setup_minutes BETWEEN 0 AND 10080),
    ADD COLUMN turnaround_minutes integer NOT NULL DEFAULT 0 CHECK (turnaround_minutes BETWEEN 0 AND 10080);

CREATE TABLE venue_unavailability (
    unavailability_id uuid PRIMARY KEY,
    venue_id uuid NOT NULL REFERENCES venues(venue_id),
    start_datetime timestamptz NOT NULL,
    end_datetime timestamptz NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    created_by uuid NOT NULL REFERENCES users(user_id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (end_datetime > start_datetime)
);
CREATE INDEX idx_unavailability_venue_time ON venue_unavailability(venue_id, start_datetime, end_datetime);
CREATE TABLE venue_unavailability_bookings (
    unavailability_id uuid NOT NULL REFERENCES venue_unavailability(unavailability_id),
    booking_id uuid NOT NULL REFERENCES venue_bookings(booking_id),
    PRIMARY KEY (unavailability_id, booking_id)
);
CREATE INDEX idx_unavailability_booking ON venue_unavailability_bookings(booking_id);
ALTER TABLE venue_bookings ADD COLUMN replaces_booking_id uuid REFERENCES venue_bookings(booking_id);
CREATE UNIQUE INDEX idx_one_pending_replacement ON venue_bookings(replaces_booking_id) WHERE status = 'pending';

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_unavailable';
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable') AND venue_booking_id IS NOT NULL)
);
