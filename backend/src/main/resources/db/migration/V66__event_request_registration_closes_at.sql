-- EO: an organiser may choose when registration closes. NULL keeps the old
-- behaviour (registration closes when the event starts).
ALTER TABLE event_requests ADD COLUMN registration_closes_at timestamptz;
