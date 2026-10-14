ALTER TABLE venue_bookings
    ADD COLUMN start_datetime TIMESTAMPTZ,
    ADD COLUMN end_datetime TIMESTAMPTZ;

UPDATE venue_bookings b
SET start_datetime = e.start_datetime,
    end_datetime = e.end_datetime
FROM events e
WHERE e.event_id = b.event_id
  AND b.start_datetime IS NULL;

ALTER TABLE venue_bookings
    ALTER COLUMN start_datetime SET NOT NULL,
    ALTER COLUMN end_datetime SET NOT NULL,
    ADD CONSTRAINT chk_venue_booking_time_window CHECK (end_datetime > start_datetime);
