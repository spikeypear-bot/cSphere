-- EO10: registration closes when the event starts, so no separate closing time.
ALTER TABLE events DROP COLUMN registration_closes_at;
