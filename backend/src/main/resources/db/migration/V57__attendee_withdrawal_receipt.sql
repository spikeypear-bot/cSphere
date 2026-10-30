-- AT08: personal withdrawal receipts use the existing registration link and outbox.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'attendee_registration_withdrawn';

-- Extend the live constraint, retaining every notification shape contributed by other stories.
DO $$
DECLARE previous text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
    WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
    ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
    EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
        substring(previous FROM 8 FOR length(previous)-8) ||
        ' OR (type::text = ''attendee_registration_withdrawn'' AND event_id IS NOT NULL'
        ' AND registration_id IS NOT NULL AND message IS NOT NULL))';
END $$;
