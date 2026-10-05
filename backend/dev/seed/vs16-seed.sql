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
    r record;
    present integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM venues WHERE venue_id=v) THEN
        INSERT INTO venues (venue_id,venue_address,venue_capacity,supported_layouts,
            venue_accessibilities,venue_facilities,operating_information,additional_information)
        VALUES (v,'[DEMO VS16] Seminar Room - local development only',200,
            ARRAY['classroom','theatre'],ARRAY['step_free_access','elevators']::accessibilities[],
            ARRAY['projection','audio_visual_equipment']::facilities[],
            'Mon-Fri 09:00-18:00','Demo venue: use the Level 2 entrance.');
    ELSIF NOT EXISTS (SELECT 1 FROM venues WHERE venue_id=v
        AND venue_address='[DEMO VS16] Seminar Room - local development only') THEN
        RAISE EXCEPTION 'VS16 venue ID collision; refusing to change records.';
    END IF;
    FOR r IN SELECT * FROM (VALUES
        ('16000000-0000-4000-8000-000000000002'::uuid,'16000000-0000-4000-8000-000000000003'::uuid,'VS16 Demo Workshop','2026-09-24 14:00:00+08'::timestamptz,'2026-09-24 17:00:00+08'::timestamptz,120,'Classroom seating for 120 people. Keep aisles clear.',ARRAY['step_free_access']::accessibilities[],'Projector and two microphones.'),
        ('16000000-0000-4000-8000-000000000004'::uuid,'16000000-0000-4000-8000-000000000005'::uuid,'VS16 Demo Seminar','2026-09-25 09:00:00+08'::timestamptz,'2026-09-25 12:00:00+08'::timestamptz,180,'Theatre seating for 180 people with a clear centre aisle.',ARRAY['step_free_access','elevators']::accessibilities[],'Projector and a wireless microphone.'),
        ('16000000-0000-4000-8000-000000000006'::uuid,'16000000-0000-4000-8000-000000000007'::uuid,'VS16 Demo Planning Session','2026-09-28 10:00:00+08'::timestamptz,'2026-09-28 12:00:00+08'::timestamptz,40,'Classroom seating for 40 people in small discussion groups.','{}'::accessibilities[],NULL::text),
        ('16000000-0000-4000-8000-000000000008'::uuid,'16000000-0000-4000-8000-000000000009'::uuid,'VS16 Demo Team Training','2026-10-07 09:00:00+08'::timestamptz,'2026-10-07 12:00:00+08'::timestamptz,60,'Classroom seating with a clear presentation area.',ARRAY['step_free_access']::accessibilities[],'Projector and one microphone.'),
        ('16000000-0000-4000-8000-000000000010'::uuid,'16000000-0000-4000-8000-000000000011'::uuid,'VS16 Demo Product Briefing','2026-10-08 09:00:00+08'::timestamptz,'2026-10-08 12:00:00+08'::timestamptz,140,'Theatre seating facing the presentation screen.',ARRAY['step_free_access']::accessibilities[],'Projector and two wireless microphones.'),
        ('16000000-0000-4000-8000-000000000012'::uuid,'16000000-0000-4000-8000-000000000013'::uuid,'VS16 Demo Leadership Workshop','2026-10-09 09:00:00+08'::timestamptz,'2026-10-09 12:00:00+08'::timestamptz,45,'Classroom seating for small group exercises.',ARRAY['step_free_access']::accessibilities[],'Projector.'),
        ('16000000-0000-4000-8000-000000000014'::uuid,'16000000-0000-4000-8000-000000000015'::uuid,'VS16 Demo Community Seminar','2026-10-12 09:00:00+08'::timestamptz,'2026-10-12 12:00:00+08'::timestamptz,180,'Theatre seating with wide aisles and accessible front rows.',ARRAY['step_free_access']::accessibilities[],'Projector and a wireless microphone.'),
        ('16000000-0000-4000-8000-000000000016'::uuid,'16000000-0000-4000-8000-000000000017'::uuid,'VS16 Demo Project Kickoff','2026-10-13 09:00:00+08'::timestamptz,'2026-10-13 12:00:00+08'::timestamptz,80,'Classroom seating with space for team discussions.',ARRAY['step_free_access']::accessibilities[],'Projector and two microphones.'),
        ('16000000-0000-4000-8000-000000000018'::uuid,'16000000-0000-4000-8000-000000000019'::uuid,'VS16 Demo Staff Orientation','2026-10-14 09:00:00+08'::timestamptz,'2026-10-14 12:00:00+08'::timestamptz,100,'Classroom seating with a clear entrance route.',ARRAY['step_free_access']::accessibilities[],'Projector.'),
        ('16000000-0000-4000-8000-000000000020'::uuid,'16000000-0000-4000-8000-000000000021'::uuid,'VS16 Demo Partner Presentation','2026-10-15 09:00:00+08'::timestamptz,'2026-10-15 12:00:00+08'::timestamptz,120,'Theatre seating with a central presentation area.',ARRAY['step_free_access']::accessibilities[],'Projector and two microphones.'),
        ('16000000-0000-4000-8000-000000000022'::uuid,'16000000-0000-4000-8000-000000000023'::uuid,'VS16 Demo Design Review','2026-10-16 09:00:00+08'::timestamptz,'2026-10-16 12:00:00+08'::timestamptz,35,'Classroom seating for interactive discussion.',ARRAY['step_free_access']::accessibilities[],'Projector.'),
        ('16000000-0000-4000-8000-000000000024'::uuid,'16000000-0000-4000-8000-000000000025'::uuid,'VS16 Demo Safety Briefing','2026-10-19 09:00:00+08'::timestamptz,'2026-10-19 12:00:00+08'::timestamptz,160,'Theatre seating; keep all exits and aisles clear.',ARRAY['step_free_access']::accessibilities[],'Projector and one microphone.'),
        ('16000000-0000-4000-8000-000000000026'::uuid,'16000000-0000-4000-8000-000000000027'::uuid,'VS16 Demo Planning Workshop','2026-10-20 09:00:00+08'::timestamptz,'2026-10-20 12:00:00+08'::timestamptz,50,'Classroom seating for working groups.',ARRAY['step_free_access']::accessibilities[],'Projector.')
    ) AS fixtures(event_id,booking_id,name,starts,ends,attendance,requirements,accessibility,equipment)
    LOOP
        SELECT (SELECT count(*) FROM events WHERE event_id=r.event_id)
             + (SELECT count(*) FROM venue_bookings WHERE booking_id=r.booking_id) INTO present;
        IF present = 0 THEN
            INSERT INTO events (event_id,event_name,purpose,description,start_datetime,end_datetime,
                expected_attendance,venue_id,accessibility_needs,venue_requirements,equipment_requirements,
                organisation,status)
            VALUES (r.event_id,r.name,'VS16 local development fixture','Synthetic demonstration data; not a real event.',
                r.starts,r.ends,r.attendance,v,r.accessibility,r.requirements,r.equipment,'VS16_LOCAL_SEED','confirmed');
            INSERT INTO venue_bookings (booking_id,venue_id,event_id,status,booking_notes)
            VALUES (r.booking_id,v,r.event_id,'pending','VS16_LOCAL_SEED');
            RAISE NOTICE 'Created % and its pending booking.', r.name;
            CONTINUE;
        END IF;
        IF present <> 2 OR NOT EXISTS (
            SELECT 1 FROM events WHERE event_id=r.event_id AND venue_id=v
              AND organisation='VS16_LOCAL_SEED' AND purpose='VS16 local development fixture'
        ) OR NOT EXISTS (
            SELECT 1 FROM venue_bookings WHERE booking_id=r.booking_id AND venue_id=v
              AND event_id=r.event_id AND booking_notes='VS16_LOCAL_SEED'
        ) THEN
            RAISE EXCEPTION 'Partial/colliding VS16 fixture or changed identity for event %. Nothing changed.', r.event_id;
        END IF;
        RAISE NOTICE 'Preserving existing fixture % and all test edits.', r.event_id;
    END LOOP;
END
$seed$;
COMMIT;
