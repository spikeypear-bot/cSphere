-- VS17: invalidated approvals retain their reservation.
ALTER TYPE venue_booking_status ADD VALUE IF NOT EXISTS 'attention';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_suitability_changed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_arrangements_changed';
