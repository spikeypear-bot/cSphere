-- V59 / AT09: attendee notices snapshot committed event changes and cancellations.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'attendee_event_changed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'attendee_event_cancelled';

ALTER TABLE notifications
    ADD COLUMN notification_changes jsonb,
    ADD COLUMN original_event_start_datetime timestamptz,
    ADD COLUMN original_event_end_datetime timestamptz;

-- Keep every notification shape contributed by prior stories.
DO $$
DECLARE previous text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
    WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
    ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
    EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
        substring(previous FROM 8 FOR length(previous)-8) ||
        ' OR (type::text = ''attendee_event_changed'' AND event_id IS NOT NULL'
        ' AND registration_id IS NOT NULL AND message IS NOT NULL'
        ' AND notification_changes IS NOT NULL AND jsonb_typeof(notification_changes) = ''array'''
        ' AND jsonb_array_length(notification_changes) > 0)'
        ' OR (type::text = ''attendee_event_cancelled'' AND event_id IS NOT NULL'
        ' AND registration_id IS NOT NULL AND message IS NOT NULL'
        ' AND original_event_start_datetime IS NOT NULL AND original_event_end_datetime IS NOT NULL))';
END $$;
