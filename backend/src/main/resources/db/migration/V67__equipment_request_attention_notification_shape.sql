-- EC-NEW3 (D37): V53 added the 'equipment_request_attention' notification type
-- but no later migration let chk_notification_shape accept it, so every such
-- notice to the coordinator was refused on delivery and stayed queued in
-- notification_outbox. NotificationService.createEquipmentRequestAttentionNotification
-- always sets the event, the reason and the message.
-- Extend the current constraint in place (V45/V50/V51/V65's pattern) instead of
-- re-listing every prior branch, so this never risks dropping one.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
  substring(previous FROM 8 FOR length(previous)-8) ||
  ' OR (type::text = ''equipment_request_attention'' AND event_id IS NOT NULL AND message IS NOT NULL))';
END $$;
