-- EC-NEW3 (D40): a coordinator's revision of an approved equipment request
-- waits for Technical Support while the requests it replaces keep their
-- reservations. Approving a revision supersedes them and releases only the
-- reservations it no longer needs; rejecting it leaves them as they were.
-- A TS09/TS11 additional request is not a revision and replaces nothing.
ALTER TABLE equipment_requests ADD COLUMN revision BOOLEAN NOT NULL DEFAULT FALSE;
