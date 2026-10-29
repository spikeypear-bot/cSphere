-- EC-NEW4: what an Event Coordinator (and the Lead) is told, and by whom.
--
-- actor_name/actor_role: who did what the notification is about ("assigned
-- by ecl1, Event Coordinator Lead"), captured when it is written. Null for
-- what the system does on its own (a hold expiring) and for older rows.
ALTER TABLE notifications
    ADD COLUMN actor_name text,
    ADD COLUMN actor_role text;

-- request_submitted: a new request is waiting to be assigned (to the Leads).
-- request_assigned: the Lead assigned or transferred a request to this coordinator.
-- request_handed_over: the Lead moved it to another coordinator (to the previous one).
-- equipment_request_decided: Technical Support approved or rejected the coordinator's equipment request.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'request_submitted';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'request_assigned';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'request_handed_over';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'equipment_request_decided';
-- EO-C1: a confirmed event's venue changed, written for its organisers (who,
-- unlike staff, see the whole message). Kept apart from VS17's
-- venue_arrangements_changed, whose message is staff reassessment detail.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'confirmed_venue_changed';

-- Extends the existing shape rule (as V51 does) rather than restating it, so
-- rules added by other migrations are kept.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text IN (''request_submitted'', ''request_assigned'', ''request_handed_over'')'
 ' AND event_request_id IS NOT NULL AND message IS NOT NULL)'
 ' OR (type::text IN (''equipment_request_decided'', ''confirmed_venue_changed'') AND event_id IS NOT NULL AND message IS NOT NULL))';
END $$;

-- "Mark all as read" and the unread badge read a recipient's unread rows.
CREATE INDEX IF NOT EXISTS idx_notifications_unread
    ON notifications(recipient_user_id, occurred_at) WHERE read_at IS NULL;

COMMENT ON COLUMN notifications.actor_name IS
    'EC-NEW4: username of whoever did what this notification is about; null when the system did it.';
