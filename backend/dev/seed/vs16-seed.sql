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
        INSERT INTO venues (venue_id,venue_address,venue_capacity,supported_layouts,
            venue_accessibilities,venue_facilities,operating_information,additional_information)
        VALUES (v,'[DEMO VS16] Seminar Room - local development only',200,
            ARRAY['classroom','theatre'],ARRAY['step_free_access','elevators']::accessibilities[],
            ARRAY['projection','audio_visual_equipment']::facilities[],
            'Mon-Fri 09:00-18:00','Demo venue: use the Level 2 entrance.');
        INSERT INTO events (event_id,event_name,purpose,description,start_datetime,end_datetime,
            expected_attendance,venue_id,accessibility_needs,venue_requirements,equipment_requirements,
            organisation,status)
        VALUES (e,'VS16 Demo Workshop','VS16 local development fixture',
            'Synthetic demonstration data; not a real event.',
            '2026-09-24 14:00:00+08','2026-09-24 17:00:00+08',120,v,
            ARRAY['step_free_access']::accessibilities[],
            'Classroom seating for 120 people. Keep aisles clear.',
            'Projector and two microphones.','VS16_LOCAL_SEED','confirmed');
        INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes)
        VALUES (b,v,e,'pending','VS16_LOCAL_SEED');
        RAISE NOTICE 'Created VS16 demo venue, event and pending booking.';
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
    RAISE NOTICE 'VS16 demo already exists; preserving all test edits.';
END
$seed$;
COMMIT;
