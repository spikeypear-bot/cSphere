-- VS17: append-only assessments, separate from approved requirement snapshots.
CREATE TABLE venue_booking_assessments (
 assessment_id uuid PRIMARY KEY,
 booking_id uuid NOT NULL REFERENCES venue_bookings(booking_id),
 outcome text NOT NULL CHECK(outcome IN ('attention','restored')),
 source text NOT NULL,
 reason text NOT NULL,
 actor_id uuid REFERENCES users(user_id),
 actor_name text,
 created_at timestamptz NOT NULL DEFAULT now(),
 venue_snapshot jsonb NOT NULL,
 requirements_snapshot jsonb NOT NULL
);
CREATE INDEX idx_booking_assessments ON venue_booking_assessments(booking_id,created_at DESC);
CREATE FUNCTION preserve_booking_assessments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Booking assessment history cannot be changed'; END $$;
CREATE TRIGGER trg_preserve_booking_assessments BEFORE UPDATE OR DELETE ON venue_booking_assessments
FOR EACH ROW EXECUTE FUNCTION preserve_booking_assessments();
-- Extend, rather than replace, notification shapes belonging to other workflows.
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text IN (''venue_suitability_changed'',''venue_arrangements_changed'') AND event_id IS NOT NULL AND venue_booking_id IS NOT NULL AND message IS NOT NULL))';
END $$;
