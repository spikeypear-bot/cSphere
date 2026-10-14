-- Manual local development only; never run against production.
\set ON_ERROR_STOP on
\if :{?vsc2_local_dev}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Pass -v vsc2_local_dev=on against a local development database only'; END $$;
\endif
\if :vsc2_local_dev
\else
  DO $$ BEGIN RAISE EXCEPTION 'vsc2_local_dev must be on'; END $$;
\endif

BEGIN;
LOCK TABLE venues, events, venue_bookings IN SHARE ROW EXCLUSIVE MODE;
DO $seed$
DECLARE
    ec uuid; eo uuid; r record; v uuid; e uuid; b uuid;
    base_day date := (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Singapore')::date + 1;
BEGIN
    SELECT user_id INTO ec FROM users WHERE username='ec1' AND role='ec';
    SELECT user_id INTO eo FROM users WHERE username='eo1' AND role='eo';
    IF ec IS NULL OR eo IS NULL THEN RAISE EXCEPTION 'Start the dev-profile backend to seed ec1 and eo1 first'; END IF;
    FOR r IN SELECT * FROM (VALUES (1,'Hall',30,45),(2,'Zero buffer room',0,0),(3,'Studio',15,60)) x(n,label,setup,turnaround) LOOP
        v := md5('vsc2-venue-' || r.n)::uuid;
        IF EXISTS(SELECT 1 FROM venues WHERE venue_id=v AND venue_address<>('[DEMO VS-C2] ' || r.label)) THEN
            RAISE EXCEPTION 'VS-C2 venue ID collision';
        END IF;
        INSERT INTO venues(venue_id,venue_address,venue_capacity,supported_layouts,operating_information,setup_minutes,turnaround_minutes)
        VALUES (v,'[DEMO VS-C2] ' || r.label,200,ARRAY['classroom','theatre'],'Daily 00:00–23:59',r.setup,r.turnaround)
        ON CONFLICT(venue_id) DO NOTHING;
        INSERT INTO venue_operating_hours(operating_hour_id,venue_id,day_of_week,open_time,close_time)
        SELECT md5('vsc2-hours-' || r.n || '-' || day)::uuid,v,day,'00:00'::time,'23:59'::time FROM generate_series(1,7) day
        WHERE NOT EXISTS(SELECT 1 FROM venue_operating_hours h WHERE h.venue_id=v AND h.day_of_week=day);
    END LOOP;
    FOR r IN SELECT * FROM (VALUES
        (1,1,'Morning workshop','09:00','10:00',0,30,45,'approved'),
        (2,1,'Legacy overlapping seminar','10:30','11:30',0,15,30,'approved'),
        (3,1,'Touching occupied boundary','12:00','13:00',0,0,15,'approved'),
        (4,1,'Pending request','14:00','15:00',0,10,20,'pending'),
        (5,1,'Rejected request','16:00','17:00',0,45,0,'rejected'),
        (6,1,'Setup crosses midnight','00:15','01:00',1,30,0,'pending'),
        (7,2,'Zero buffer first','10:00','11:00',0,0,0,'approved'),
        (8,2,'Zero buffer adjacent','11:00','12:00',0,0,0,'approved'),
        (9,3,'Studio preparation','10:00','12:00',0,60,30,'pending')
    ) x(n,venue_n,label,start_time,end_time,day_offset,setup,turnaround,status) LOOP
        v := md5('vsc2-venue-' || r.venue_n)::uuid;
        e := md5('vsc2-event-' || r.n)::uuid;
        b := md5('vsc2-booking-' || r.n)::uuid;
        IF EXISTS(SELECT 1 FROM events WHERE event_id=e AND event_name<>('[DEMO VS-C2] ' || r.label))
            OR EXISTS(SELECT 1 FROM venue_bookings WHERE booking_id=b AND (venue_id<>v OR event_id<>e)) THEN
            RAISE EXCEPTION 'VS-C2 event/booking ID collision';
        END IF;
        INSERT INTO events(event_id,event_name,purpose,start_datetime,end_datetime,expected_attendance,venue_id,
                venue_requirements,equipment_requirements,status,coordinator_id,organisation)
        VALUES(e,'[DEMO VS-C2] ' || r.label,'Timing demonstration',
                (base_day+r.day_offset+r.start_time::time) AT TIME ZONE 'Asia/Singapore',
                (base_day+r.day_offset+r.end_time::time) AT TIME ZONE 'Asia/Singapore',100,v,
                'Arrange early crew access and allow time for teardown. Coordinator to agree durations with Venue Staff.',
                'Projector setup before advertised opening.','pending',ec,'Acme Pte Ltd')
        ON CONFLICT(event_id) DO NOTHING;
        INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,setup_duration_minutes,turnaround_duration_minutes,
                booking_notes,timing_note,submitted_by,submitted_at,reject_reason)
        VALUES(b,v,e,r.status::venue_booking_status,r.setup,r.turnaround,'Review organiser setup notes.',
                'Demonstration of booking-specific crew access.',ec,CURRENT_TIMESTAMP,
                CASE WHEN r.status='rejected' THEN 'Demo rejection' ELSE NULL END)
        ON CONFLICT(booking_id) DO NOTHING;
    END LOOP;
    -- Existing free-text EO requirements, linked to the first event for EC review.
    IF EXISTS(SELECT 1 FROM event_requests WHERE request_id=md5('vsc2-request')::uuid
            AND event_name<>'[DEMO VS-C2] Morning workshop') THEN RAISE EXCEPTION 'VS-C2 request ID collision'; END IF;
    INSERT INTO event_requests(request_id,request_type,event_id,event_name,purpose,start_datetime,end_datetime,
            expected_attendance,venue_requirements,equipment_requirements,status,created_by,coordinator_id,organisation)
    SELECT md5('vsc2-request')::uuid,'C',event_id,event_name,purpose,start_datetime,end_datetime,
            expected_attendance,venue_requirements,equipment_requirements,'approved',eo,ec,'Acme Pte Ltd'
    FROM events WHERE event_id=md5('vsc2-event-1')::uuid
    ON CONFLICT(request_id) DO NOTHING;
END $seed$;
COMMIT;
