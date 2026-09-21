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
    e constant uuid := '16000000-0000-4000-8000-000000000002';
    b constant uuid := '16000000-0000-4000-8000-000000000003';
    present integer;
BEGIN
    SELECT (SELECT count(*) FROM venues WHERE venue_id=v)
         + (SELECT count(*) FROM events WHERE event_id=e)
         + (SELECT count(*) FROM venue_bookings WHERE booking_id=b) INTO present;
    IF present = 0 THEN
        RAISE NOTICE 'No VS16 fixture exists; nothing to clean up.';
        RETURN;
    END IF;
    IF present <> 3 THEN
        RAISE EXCEPTION 'Partial VS16 fixture detected; inspect reserved IDs manually. Nothing changed.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM venues WHERE venue_id=v AND venue_address='[DEMO VS16] Seminar Room - local development only')
       OR NOT EXISTS (SELECT 1 FROM events WHERE event_id=e AND organisation='VS16_LOCAL_SEED'
                      AND purpose='VS16 local development fixture' AND venue_id=v)
       OR NOT EXISTS (SELECT 1 FROM venue_bookings WHERE booking_id=b AND venue_id=v AND event_id=e
                      AND booking_notes='VS16_LOCAL_SEED') THEN
        RAISE EXCEPTION 'VS16 ID collision or changed fixture identity/relationship; refusing to alter records.';
    END IF;
    IF EXISTS (SELECT 1 FROM venue_bookings WHERE booking_id<>b AND (venue_id=v OR event_id=e))
       OR EXISTS (SELECT 1 FROM events WHERE event_id<>e AND venue_id=v)
       OR EXISTS (SELECT 1 FROM event_requests WHERE event_id=e)
       OR EXISTS (SELECT 1 FROM equipment_requests WHERE event_id=e)
       OR EXISTS (SELECT 1 FROM equipment_logs WHERE event_id=e) THEN
        RAISE EXCEPTION 'Other records reference VS16 fixtures; refusing cleanup. Nothing changed.';
    END IF;
    DELETE FROM venue_bookings WHERE booking_id=b;
    DELETE FROM events WHERE event_id=e;
    DELETE FROM venues WHERE venue_id=v;
    RAISE NOTICE 'Removed only the VS16 demo booking, event and venue.';
END
$seed$;
COMMIT;
