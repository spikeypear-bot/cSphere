-- Supports EO01 ("save an incomplete event request as a draft") and EO02
-- ("submit a completed event request"), which the schema as of V2 could not
-- represent: event_request_status had no 'draft' value, and event_name,
-- purpose, expected_attendance, venue_requirements were NOT NULL, so an
-- incomplete request could not be saved at all.
--
-- Fix: add 'draft' to the status enum, and relax those four columns to
-- nullable — the same pattern V2 already used for start_datetime/end_datetime
-- (see SCHEMA.md gap #4). Completeness for *submission* is enforced in the
-- application layer (EventRequestService.submit), not the database.
--
-- 'submitted for review' maps to the existing 'pending' value rather than
-- adding a new one — the briefing's "submitted"/"under review" language
-- describes the same request-awaiting-coordinator-action state.
--
-- Also resolves the request_type TODO from SCHEMA.md: 'C' = creation request
-- (event_id IS NULL), 'A' = amendment/change request (event_id IS NOT NULL).
-- Chosen to avoid the "both start with C" collision SCHEMA.md flagged.

ALTER TYPE event_request_status ADD VALUE IF NOT EXISTS 'draft';

ALTER TABLE event_requests ALTER COLUMN event_name DROP NOT NULL;
ALTER TABLE event_requests ALTER COLUMN purpose DROP NOT NULL;
ALTER TABLE event_requests ALTER COLUMN expected_attendance DROP NOT NULL;
ALTER TABLE event_requests ALTER COLUMN venue_requirements DROP NOT NULL;

COMMENT ON COLUMN event_requests.request_type IS
    'C = creation request (event_id IS NULL); A = amendment/change request (event_id IS NOT NULL).';
