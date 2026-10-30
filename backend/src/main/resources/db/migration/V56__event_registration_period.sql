-- EO12 restores a separately configured close time while preserving EO10's
-- existing event-start cutoff for every event already in the database.
ALTER TABLE events ADD COLUMN registration_closes_at timestamptz;

UPDATE events
SET registration_closes_at = start_datetime
WHERE registration_closes_at IS NULL
  AND start_datetime IS NOT NULL;
