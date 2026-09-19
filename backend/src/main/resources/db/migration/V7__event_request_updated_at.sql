-- EO01/EO15 UX enhancement: "last edited X minutes ago" on a draft needs a
-- real last-modified timestamp — created_at alone can't tell a fresh draft
-- from one edited five times over three days. Backfill existing rows from
-- created_at (their true last-modified time, since nothing has updated them
-- since this column didn't exist), then require it going forward.
ALTER TABLE event_requests ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE event_requests SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE event_requests
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

COMMENT ON COLUMN event_requests.updated_at IS
    'Set by EventRequestService on every save/submit — not a DB trigger, so an application-layer write that forgets to touch it is still possible; see EventRequestService for the single place this is set.';
