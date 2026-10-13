ALTER TABLE venue_bookings
    ALTER COLUMN start_datetime DROP NOT NULL,
    ALTER COLUMN end_datetime DROP NOT NULL;
