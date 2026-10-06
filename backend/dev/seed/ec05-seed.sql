-- Manual local-development fixture for EC05 coordinator review and venue suitability.
\set ON_ERROR_STOP on
\if :{?ec05_local_dev}
\else
  \echo 'Refusing: pass -v ec05_local_dev=on only against a local development database.'
  DO $$ BEGIN RAISE EXCEPTION 'EC05 local-development opt-in is required'; END $$;
\endif
\if :ec05_local_dev
\else
  \echo 'Refusing: ec05_local_dev must be on.'
  DO $$ BEGIN RAISE EXCEPTION 'EC05 local-development opt-in is required'; END $$;
\endif

BEGIN;
DO $seed$
DECLARE
    seeded_request_id constant uuid := 'ec050000-0000-4000-8000-000000000001';
    seeded_venue_id constant uuid := '16000000-0000-4000-8000-000000000001';
    creator uuid;
BEGIN
    SELECT user_id INTO creator
    FROM users
    WHERE role = 'eo'
    ORDER BY created_at, user_id
    LIMIT 1;

    IF creator IS NULL THEN
        RAISE EXCEPTION 'EC05 seed requires at least one Event Organiser account.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM venues AS seeded_venue WHERE seeded_venue.venue_id = seeded_venue_id) THEN
        RAISE EXCEPTION 'EC05 seed requires the VS16 demo venue. Run vs16-seed.sql first.';
    END IF;

    INSERT INTO venue_operating_hours (operating_hour_id, venue_id, day_of_week, open_time, close_time)
    VALUES
        ('ec050000-0000-4000-8000-000000000011', seeded_venue_id, 1, '09:00', '18:00'),
        ('ec050000-0000-4000-8000-000000000012', seeded_venue_id, 2, '09:00', '18:00'),
        ('ec050000-0000-4000-8000-000000000013', seeded_venue_id, 3, '09:00', '18:00'),
        ('ec050000-0000-4000-8000-000000000014', seeded_venue_id, 4, '09:00', '18:00'),
        ('ec050000-0000-4000-8000-000000000015', seeded_venue_id, 5, '09:00', '18:00')
    ON CONFLICT (venue_id, day_of_week) DO NOTHING;

    IF EXISTS (SELECT 1 FROM event_requests AS seeded_request
               WHERE seeded_request.request_id = seeded_request_id) THEN
        IF NOT EXISTS (
            SELECT 1 FROM event_requests AS seeded_request
            WHERE seeded_request.request_id = seeded_request_id
              AND seeded_request.event_name = 'EC05 Venue Suitability Demonstration'
              AND seeded_request.organisation = 'EC05_LOCAL_SEED'
        ) THEN
            RAISE EXCEPTION 'EC05 request ID collision; refusing to change records.';
        END IF;
        RAISE NOTICE 'EC05 review fixture already exists; preserving it and any test edits.';
        RETURN;
    END IF;

    INSERT INTO event_requests (
        request_id, request_type, event_name, purpose, description,
        start_datetime, end_datetime, expected_attendance, venue_requirements,
        equipment_requirements, accessibility_needs, required_facilities,
        registration_needs, status, organisation, created_by, coordinator_id
    )
    SELECT
        seeded_request_id, 'C', 'EC05 Venue Suitability Demonstration',
        'Demonstrate coordinator venue comparison',
        'Synthetic local-development request for EC05 manual testing.',
        '2026-10-07 09:00:00+08'::timestamptz,
        '2026-10-07 12:00:00+08'::timestamptz,
        120,
        'Classroom seating with a clear presentation area.',
        'Projector and one microphone.',
        ARRAY['step_free_access']::accessibilities[],
        ARRAY['projection']::facilities[],
        false, 'pending', 'EC05_LOCAL_SEED', creator, NULL;

    RAISE NOTICE 'Created EC05 review fixture %.', seeded_request_id;
END
$seed$;
COMMIT;
