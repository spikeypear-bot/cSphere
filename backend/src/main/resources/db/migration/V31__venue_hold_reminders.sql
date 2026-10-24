-- EC-C3: remind the event's coordinator before a tentative venue hold expires.
-- reminded_at records when this hold attempt's reminder was sent; NULL means not yet.
-- A new hold on the same booking is a new row, so it gets its own reminder.
ALTER TABLE venue_booking_holds ADD COLUMN reminded_at timestamptz;

-- The new value is not used in this transaction: the constraint below compares type::text.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_hold_expiring';

-- V30's constraint, with venue_hold_expiring added to the booking-reference branch.
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
);
