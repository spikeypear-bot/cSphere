-- AT01: registration settings travel from an event request to its approved event.
ALTER TABLE venues ADD COLUMN venue_name TEXT;
UPDATE venues SET venue_name = venue_address WHERE venue_name IS NULL;
ALTER TABLE venues ALTER COLUMN venue_name SET NOT NULL;

ALTER TABLE event_requests
    ADD COLUMN registration_capacity INTEGER,
    ADD COLUMN registration_opens_at TIMESTAMPTZ,
    ADD COLUMN registration_closes_at TIMESTAMPTZ,
    ADD COLUMN attendee_instructions TEXT,
    ADD CONSTRAINT chk_event_request_registration_capacity
        CHECK (registration_capacity IS NULL OR registration_capacity > 0);

ALTER TABLE events
    ADD COLUMN registration_capacity INTEGER,
    ADD COLUMN registration_opens_at TIMESTAMPTZ,
    ADD COLUMN registration_closes_at TIMESTAMPTZ,
    ADD COLUMN attendee_instructions TEXT,
    ADD CONSTRAINT chk_event_registration_capacity
        CHECK (registration_capacity IS NULL OR registration_capacity > 0);

-- The registration row is the reservation. The event row is locked while
-- checking the window and count, so all submissions for one event serialize.
CREATE TABLE event_registrations (
    registration_id UUID PRIMARY KEY,
    registration_reference VARCHAR(40) NOT NULL UNIQUE,
    event_id UUID NOT NULL REFERENCES events(event_id),
    attendee_id UUID NOT NULL REFERENCES users(user_id),
    attendee_name VARCHAR(255) NOT NULL,
    attendee_email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'confirmed'
        CHECK (status IN ('confirmed', 'cancelled')),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_active_registration_per_attendee_event
    ON event_registrations(event_id, attendee_id)
    WHERE status = 'confirmed';

CREATE INDEX idx_event_registrations_event_status
    ON event_registrations(event_id, status);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'attendee_registration_confirmed';

-- Extend V33's full shape check with the AT03 confirmation delivered to the attendee.
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
    OR (type::text = 'attendee_registration_confirmed' AND event_id IS NOT NULL AND message IS NOT NULL)
);
