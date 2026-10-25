-- TS02: a reservation can be marked as a spare: an extra unit held beyond
-- what the event's requests ask for (a backup projector, say). A reservation
-- may not take an event above its requested quantity unless the excess is
-- marked spare (decision-log D33). Existing rows are not spares.
-- V34-V37 are taken across branches.

ALTER TABLE equipment_logs
    ADD COLUMN spare BOOLEAN NOT NULL DEFAULT false;
