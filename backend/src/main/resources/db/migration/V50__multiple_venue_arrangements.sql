-- EC-C2 EC08 EC10 SO-C2 VS04: preserve independent arrangements and their evidence.
ALTER TABLE venue_bookings ADD COLUMN requirements jsonb;
CREATE TABLE venue_booking_requirements (
 requirement_id uuid PRIMARY KEY,
 event_id uuid NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
 withdrawn_at timestamptz, withdrawn_by uuid REFERENCES users(user_id), withdrawal_reason text
);
ALTER TABLE venue_bookings ADD COLUMN requirement_id uuid REFERENCES venue_booking_requirements(requirement_id);
-- Backfill active replacement chains into a single requirement. Terminal-only chains are historical.
WITH RECURSIVE roots AS (
 SELECT booking_id, booking_id AS root_id FROM venue_bookings WHERE replaces_booking_id IS NULL
 UNION ALL SELECT b.booking_id,r.root_id FROM venue_bookings b JOIN roots r ON b.replaces_booking_id=r.booking_id
)
INSERT INTO venue_booking_requirements(requirement_id,event_id)
SELECT DISTINCT r.root_id,b.event_id FROM roots r JOIN venue_bookings b USING(booking_id)
WHERE b.status IN ('pending','approved','attention');
WITH RECURSIVE roots AS (
 SELECT booking_id, booking_id AS root_id FROM venue_bookings WHERE replaces_booking_id IS NULL
 UNION ALL SELECT b.booking_id,r.root_id FROM venue_bookings b JOIN roots r ON b.replaces_booking_id=r.booking_id
)
UPDATE venue_bookings b SET requirement_id=r.root_id FROM roots r
WHERE b.booking_id=r.booking_id AND EXISTS(SELECT 1 FROM venue_booking_requirements q WHERE q.requirement_id=r.root_id);
CREATE INDEX idx_booking_requirement ON venue_bookings(requirement_id);
ALTER TABLE event_readiness_items ADD COLUMN arrangement_fingerprint text;
-- Preserve SO-C5/SO-C6's standard feedback keys and allow the per-booking
-- checklist keys introduced here. The service checks membership in this event.
CREATE FUNCTION valid_safety_flagged_items(items text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
 SELECT NOT EXISTS (
   SELECT 1 FROM unnest(items) AS item
   WHERE item IS NULL OR NOT (
     item = ANY(ARRAY['venue_booking', 'capacity_facilities', 'accessibility',
       'equipment', 'registration', 'safety_operations'])
     OR item ~ '^venue_safety_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   )
 );
$$;
ALTER TABLE event_safety_reviews DROP CONSTRAINT chk_safety_review_flagged_items;
ALTER TABLE event_safety_reviews ADD CONSTRAINT chk_safety_review_flagged_items
 CHECK (valid_safety_flagged_items(flagged_items));
CREATE TABLE venue_booking_history (
 history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 booking_id uuid NOT NULL REFERENCES venue_bookings(booking_id) ON DELETE CASCADE,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 actor_id uuid REFERENCES users(user_id),
 previous_booking jsonb, booking jsonb NOT NULL, event_requirements jsonb NOT NULL
);
CREATE INDEX idx_booking_history ON venue_booking_history(booking_id,history_id);
CREATE FUNCTION record_venue_booking_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND to_jsonb(OLD)=to_jsonb(NEW) THEN RETURN NEW; END IF;
 INSERT INTO venue_booking_history(booking_id,actor_id,previous_booking,booking,event_requirements)
 SELECT NEW.booking_id,nullif(current_setting('connectsphere.actor_id',true),'')::uuid,
 CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE NULL END,to_jsonb(NEW),
 jsonb_build_object('expectedAttendance',e.expected_attendance,'requiredFacilities',e.required_facilities,
 'accessibilityNeeds',e.accessibility_needs,'venueRequirements',e.venue_requirements)
 FROM events e WHERE e.event_id=NEW.event_id;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_booking_history AFTER INSERT OR UPDATE ON venue_bookings
FOR EACH ROW EXECUTE FUNCTION record_venue_booking_history();
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_booking_rejected';
DO $$ DECLARE previous text; BEGIN
 SELECT pg_get_constraintdef(oid) INTO previous FROM pg_constraint
 WHERE conrelid='notifications'::regclass AND conname='chk_notification_shape';
 ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
 EXECUTE 'ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (' ||
 substring(previous FROM 8 FOR length(previous)-8) ||
 ' OR (type::text = ''venue_booking_rejected'' AND event_id IS NOT NULL AND venue_booking_id IS NOT NULL AND reason IS NOT NULL AND message IS NOT NULL))';
END $$;
