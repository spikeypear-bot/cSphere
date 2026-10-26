-- AT01/AT02: extend EO10's V35 schema after V36-V40, preserving existing rows.
ALTER TABLE venues ADD COLUMN venue_name TEXT;
UPDATE venues SET venue_name = venue_address;
ALTER TABLE venues ALTER COLUMN venue_name SET NOT NULL;

ALTER TABLE event_requests
    ADD COLUMN registration_capacity INTEGER CHECK (registration_capacity > 0),
    ADD COLUMN registration_opens_at TIMESTAMPTZ,
    ADD COLUMN attendee_instructions TEXT;

-- V35 already owns capacity/opening; V36 makes event start the closing time.
ALTER TABLE events ADD COLUMN attendee_instructions TEXT;

-- Historical EO10 registrations have no authenticated account/email recorded.
-- Do not invent identities or discard those rows; new AT01 writes supply both.
ALTER TABLE event_registrations
    ADD COLUMN attendee_id UUID REFERENCES users(user_id),
    ADD COLUMN attendee_email TEXT;

CREATE UNIQUE INDEX uq_active_registration_per_attendee_event
    ON event_registrations(event_id, attendee_id)
    WHERE status = 'confirmed' AND attendee_id IS NOT NULL;
CREATE INDEX idx_event_registrations_event_status
    ON event_registrations(event_id, status);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'attendee_registration_confirmed';

-- Extend V38's full shape check with the AT03 confirmation delivered to the attendee.
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
    OR (type::text = 'attendee_registration_confirmed' AND event_id IS NOT NULL AND message IS NOT NULL)
);
