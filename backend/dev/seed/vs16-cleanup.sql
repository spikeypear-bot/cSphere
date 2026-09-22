-- Manual local-development fixture only. Never a Flyway migration.
\set ON_ERROR_STOP on
\if :{?vs16_local_dev}
\else
  \echo 'Refusing: pass -v vs16_local_dev=on only against a local development database.'
  DO $$ BEGIN RAISE EXCEPTION 'VS16 local-development opt-in is required'; END $$;
\endif
\if :vs16_local_dev
\else
  \echo 'Refusing: vs16_local_dev must be on.'
  DO $$ BEGIN RAISE EXCEPTION 'VS16 local-development opt-in is required'; END $$;
\endif

BEGIN;
LOCK TABLE venues, events, venue_bookings IN SHARE ROW EXCLUSIVE MODE;
DO $seed$
DECLARE
    v constant uuid := '16000000-0000-4000-8000-000000000001';
    event_ids uuid[] := ARRAY['16000000-0000-4000-8000-000000000002','16000000-0000-4000-8000-000000000004','16000000-0000-4000-8000-000000000006']::uuid[];
    booking_ids uuid[] := ARRAY['16000000-0000-4000-8000-000000000003','16000000-0000-4000-8000-000000000005','16000000-0000-4000-8000-000000000007']::uuid[];
    r record;
    present integer;
BEGIN
    IF EXISTS (SELECT 1 FROM venues WHERE venue_id=v)
       AND NOT EXISTS (SELECT 1 FROM venues WHERE venue_id=v
           AND venue_address='[DEMO VS16] Seminar Room - local development only') THEN
        RAISE EXCEPTION 'VS16 venue ID collision; refusing cleanup.';
    END IF;
    FOR r IN SELECT event_id,booking_id FROM unnest(event_ids,booking_ids) AS ids(event_id,booking_id)
    LOOP
        SELECT (SELECT count(*) FROM events WHERE event_id=r.event_id)
             + (SELECT count(*) FROM venue_bookings WHERE booking_id=r.booking_id) INTO present;
        IF present = 0 THEN CONTINUE; END IF;
        IF present <> 2 OR NOT EXISTS (
            SELECT 1 FROM events WHERE event_id=r.event_id AND venue_id=v
              AND organisation='VS16_LOCAL_SEED' AND purpose='VS16 local development fixture'
        ) OR NOT EXISTS (
            SELECT 1 FROM venue_bookings WHERE booking_id=r.booking_id AND venue_id=v
              AND event_id=r.event_id AND booking_notes='VS16_LOCAL_SEED'
        ) THEN
            RAISE EXCEPTION 'Partial/colliding VS16 fixture or changed identity for event %. Nothing changed.', r.event_id;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM venue_bookings WHERE NOT (booking_id=ANY(booking_ids))
               AND (venue_id=v OR event_id=ANY(event_ids)))
       OR EXISTS (SELECT 1 FROM events WHERE NOT (event_id=ANY(event_ids)) AND venue_id=v)
       OR EXISTS (SELECT 1 FROM event_requests WHERE event_id=ANY(event_ids))
       OR EXISTS (SELECT 1 FROM equipment_requests WHERE event_id=ANY(event_ids))
       OR EXISTS (SELECT 1 FROM equipment_logs WHERE event_id=ANY(event_ids)) THEN
        RAISE EXCEPTION 'Other records reference VS16 fixtures; refusing cleanup. Nothing changed.';
    END IF;
    DELETE FROM venue_bookings WHERE booking_id=ANY(booking_ids);
    DELETE FROM events WHERE event_id=ANY(event_ids);
    DELETE FROM venues WHERE venue_id=v;
    RAISE NOTICE 'VS16 demo cleanup complete (already absent fixtures are a no-op).';
END
$seed$;
COMMIT;
