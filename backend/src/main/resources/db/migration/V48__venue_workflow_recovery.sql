-- Retain unavailable periods and their historical impact when staff release them.
ALTER TABLE venue_unavailability
    ADD COLUMN released_at timestamptz,
    ADD COLUMN released_by uuid REFERENCES users(user_id),
    ADD COLUMN release_reason text,
    ADD CONSTRAINT chk_unavailability_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND length(btrim(release_reason)) BETWEEN 1 AND 2000));

-- Required lifecycle work is stored in the triggering transaction and retried after commit.
CREATE TABLE venue_replan_tasks (
    task_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES users(user_id),
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz
);
CREATE INDEX idx_venue_replan_pending ON venue_replan_tasks(created_at,task_id) WHERE completed_at IS NULL;

-- IDs survive retries, so delivery cannot create a second notification for the same work item.
CREATE TABLE notification_outbox (
    notification_id uuid PRIMARY KEY,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz
);
CREATE INDEX idx_notification_outbox_pending ON notification_outbox(created_at,notification_id) WHERE delivered_at IS NULL;
