-- EO10: registration settings per event and the attendee registrations.
ALTER TABLE events
    ADD COLUMN registration_capacity integer CHECK (registration_capacity > 0),
    ADD COLUMN registration_opens_at timestamptz,
    ADD COLUMN registration_closes_at timestamptz;

CREATE TABLE event_registrations (
    registration_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES events(event_id),
    attendee_name text NOT NULL,
    reference text NOT NULL UNIQUE,
    registered_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('confirmed', 'withdrawn', 'event_cancelled'))
);

CREATE INDEX idx_event_registrations_event ON event_registrations(event_id);
