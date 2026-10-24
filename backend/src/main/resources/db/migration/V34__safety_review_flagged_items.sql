-- SO-C5 / SO-C6: a rejection or a request for changes names the readiness
-- checklist items (SO-C3) it concerns, so the coordinator sees what to fix.
-- Item keys are the checklist's own (venue_booking, capacity_facilities,
-- accessibility, equipment, registration, safety_operations), checked by
-- ReadinessService. An approval names none. Reviews decided before this
-- migration have none.
ALTER TABLE event_safety_reviews
    ADD COLUMN flagged_items TEXT[] NOT NULL DEFAULT '{}';
