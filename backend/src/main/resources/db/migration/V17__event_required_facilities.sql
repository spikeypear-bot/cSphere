ALTER TABLE event_requests
    ADD COLUMN required_facilities facilities[] NOT NULL DEFAULT '{}';

ALTER TABLE events
    ADD COLUMN required_facilities facilities[] NOT NULL DEFAULT '{}';
