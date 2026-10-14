-- Preserve the occupancy calculated by V20 for every existing booking, including
-- historical/rejected/cancelled records. No booking status or event time changes.
ALTER TABLE venue_bookings
    ADD COLUMN setup_duration_minutes integer,
    ADD COLUMN turnaround_duration_minutes integer;

UPDATE venue_bookings b
SET setup_duration_minutes = v.setup_minutes,
    turnaround_duration_minutes = v.turnaround_minutes
FROM venues v
WHERE v.venue_id = b.venue_id;

-- Zero is the compatibility default for explicit SQL fixtures/legacy writers.
-- Application booking submission snapshots the venue defaults under its lock.
ALTER TABLE venue_bookings
    ALTER COLUMN setup_duration_minutes SET NOT NULL,
    ALTER COLUMN setup_duration_minutes SET DEFAULT 0,
    ALTER COLUMN turnaround_duration_minutes SET NOT NULL,
    ALTER COLUMN turnaround_duration_minutes SET DEFAULT 0,
    ADD CONSTRAINT venue_booking_setup_duration_range CHECK (setup_duration_minutes BETWEEN 0 AND 10080),
    ADD CONSTRAINT venue_booking_turnaround_duration_range CHECK (turnaround_duration_minutes BETWEEN 0 AND 10080);
