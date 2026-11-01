-- DEV16: map supplied coordinates onto existing rooms, never create/re-identify rooms.
-- Generated from backend/dev/seed/venue_coordinates.json, SHA-256: 6148a62ee73b589f2fa52fd18e6af17e4d6b22e89a6bb9a0353b51c0f324e193
-- Source URLs intentionally omitted. Building/complex precision is recorded in
-- the mapping JSON; shared coordinates do not imply a shared bookable identity.
-- Flyway owns the transaction. Never edit an applied V60, V61 or V62.
LOCK TABLE venues IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE dev16_coordinate_source (
    venue_id uuid PRIMARY KEY,
    venue_name text NOT NULL,
    venue_address text NOT NULL,
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    UNIQUE (venue_name, venue_address)
) ON COMMIT DROP;
INSERT INTO dev16_coordinate_source (venue_id, venue_name, venue_address, latitude, longitude)
VALUES
    ('46be4c26-2ba7-57c8-80cb-f52f608e43d2', 'SMU Yong Pung How School of Law – Seminar Room 3-09, Level 3', 'SMU Yong Pung How School of Law – Seminar Room 3-09, Level 3, 55 Armenian Street, Singapore 179943', 1.29505, 103.84906),
    ('bc3f56c5-6a2a-5283-a977-fbf102f0f71c', 'SMU Yong Pung How School of Law – SMU Hall 3, Basement 1', 'SMU Yong Pung How School of Law – SMU Hall 3, Basement 1, 55 Armenian Street, Singapore 179943', 1.29505, 103.84906),
    ('036adc01-b7d8-5fc9-9d97-c5299a933d2d', 'NUS University Cultural Centre – Ho Bee Auditorium', 'NUS University Cultural Centre – Ho Bee Auditorium, 50 Kent Ridge Crescent, Singapore 119279', 1.30172864, 103.7721489),
    ('62479f09-2111-5391-8a8f-fc510e166fbc', 'NUS University Cultural Centre – Theatre', 'NUS University Cultural Centre – Theatre, 50 Kent Ridge Crescent, Singapore 119279', 1.30172864, 103.7721489),
    ('6dd9f923-7f22-5ac7-87ac-6f2f7e935972', 'NUS University Cultural Centre – Function Room 1, Level 1', 'NUS University Cultural Centre – Function Room 1, Level 1, 50 Kent Ridge Crescent, Singapore 119279', 1.30172864, 103.7721489),
    ('d077d8be-4446-5b3a-9990-7c01ab97d8d8', 'NTU – Nanyang Auditorium Theatre', 'NTU – Nanyang Auditorium Theatre, 50 Nanyang Avenue, Singapore 639798', 1.34413459, 103.68009328),
    ('31cc055b-3c23-5957-a8b8-035055d3a6d1', 'Lifelong Learning Institute – Training Room 1-1, Level 1', 'Lifelong Learning Institute – Training Room 1-1, Level 1, 11 Eunos Road 8, Singapore 408601', 1.31963, 103.89281),
    ('f61189c6-500a-5ed8-adb4-79c8781421f4', 'Lifelong Learning Institute – Event Hall 1-1, Level 1', 'Lifelong Learning Institute – Event Hall 1-1, Level 1, 11 Eunos Road 8, Singapore 408601', 1.31963, 103.89281),
    ('06eab6fa-a842-5c76-81e8-dff35e7936c2', 'Lifelong Learning Institute – Lecture Theatre', 'Lifelong Learning Institute – Lecture Theatre, 11 Eunos Road 8, Singapore 408601', 1.31963, 103.89281),
    ('c94f126a-199c-5613-9007-00afefd8ff74', 'Singapore Chinese Cultural Centre – Auditorium, Levels 9–10', 'Singapore Chinese Cultural Centre – Auditorium, Levels 9–10, 1 Straits Boulevard, Singapore 018906', 1.27582919, 103.84957639),
    ('08ef8aae-a75c-5629-9e92-04973d87cda2', 'Singapore Chinese Cultural Centre – Multi-Purpose Hall, Level 7', 'Singapore Chinese Cultural Centre – Multi-Purpose Hall, Level 7, 1 Straits Boulevard, Singapore 018906', 1.27582919, 103.84957639),
    ('a58c2a81-72b4-5859-8c20-860d39ba1def', 'Singapore Chinese Cultural Centre – Creative Box, Level 6', 'Singapore Chinese Cultural Centre – Creative Box, Level 6, 1 Straits Boulevard, Singapore 018906', 1.27582919, 103.84957639),
    ('67084cb8-b2ee-5908-9b99-010d43119e00', 'Esplanade – Concert Hall', 'Esplanade – Concert Hall, 1 Esplanade Drive, Singapore 038981', 1.28937, 103.85518),
    ('69ed6970-c0b8-5559-801d-2cf4b3e5b0fe', 'Esplanade – Recital Studio, Level 2', 'Esplanade – Recital Studio, Level 2, 1 Esplanade Drive, Singapore 038981', 1.28915389, 103.85561056),
    ('1537e784-29ce-55b1-911e-9f06464fca41', 'The Star Performing Arts Centre – The Star Theatre', 'The Star Performing Arts Centre – The Star Theatre, 1 Vista Exchange Green, Singapore 138617', 1.306895, 103.788483),
    ('a3ce78ee-ceaa-54e2-8bec-94d0f81c04b6', 'Singapore EXPO – Exhibition Hall 1', 'Singapore EXPO – Exhibition Hall 1, 1 Expo Drive, Singapore 486150', 1.334402, 103.96036),
    ('683a8a3b-40bc-54d5-9aaf-f626ce15b145', 'Sands Expo & Convention Centre – Sands Grand Ballroom', 'Sands Expo & Convention Centre – Sands Grand Ballroom, 10 Bayfront Avenue, Singapore 018956', 1.28239714, 103.85840539),
    ('d14ca2e7-27f5-5910-9962-7a8cbe53da66', 'Suntec Singapore Convention & Exhibition Centre – Hall 401, Level 4', 'Suntec Singapore Convention & Exhibition Centre – Hall 401, Level 4, 1 Raffles Boulevard, Singapore 039593', 1.29350362, 103.85719484),
    ('90429ced-61d8-5926-aeed-5e7a7807be3c', 'Victoria Theatre & Concert Hall – Victoria Concert Hall', 'Victoria Theatre & Concert Hall – Victoria Concert Hall, 9 Empress Place, Singapore 179556', 1.28838167, 103.85167833),
    ('b590ea20-590f-5fc8-a1ca-22d76537dd9d', 'The Arts House at the Old Parliament – Chamber', 'The Arts House at the Old Parliament – Chamber, 1 Old Parliament Lane, Singapore 179429', 1.28868361, 103.85106389);

-- Recognise V61 deterministic IDs, exact normalised room identities and legacy
-- address-as-name records, including rows V61 preserved under another UUID.
CREATE TEMP TABLE dev16_coordinate_matches ON COMMIT DROP AS
SELECT s.venue_id AS seed_id, v.venue_id AS target_id,
       lower(regexp_replace(btrim(v.venue_address), '[[:space:]]+', ' ', 'g')) =
       lower(regexp_replace(btrim(s.venue_address), '[[:space:]]+', ' ', 'g')) AS same_address,
       (lower(regexp_replace(btrim(v.venue_name), '[[:space:]]+', ' ', 'g')) =
        lower(regexp_replace(btrim(s.venue_name), '[[:space:]]+', ' ', 'g'))
        OR lower(regexp_replace(btrim(v.venue_name), '[[:space:]]+', ' ', 'g')) =
           lower(regexp_replace(btrim(v.venue_address), '[[:space:]]+', ' ', 'g'))) AS same_name
FROM dev16_coordinate_source s JOIN venues v ON
    v.venue_id = s.venue_id
    OR (lower(regexp_replace(btrim(v.venue_address), '[[:space:]]+', ' ', 'g')) =
        lower(regexp_replace(btrim(s.venue_address), '[[:space:]]+', ' ', 'g'))
        AND (lower(regexp_replace(btrim(v.venue_name), '[[:space:]]+', ' ', 'g')) =
             lower(regexp_replace(btrim(s.venue_name), '[[:space:]]+', ' ', 'g'))
             OR lower(regexp_replace(btrim(v.venue_name), '[[:space:]]+', ' ', 'g')) =
                lower(regexp_replace(btrim(v.venue_address), '[[:space:]]+', ' ', 'g'))));

DO $$
DECLARE
    problems text;
    rejected integer;
    matched integer;
    filled integer;
    missing integer;
    detail record;
BEGIN
    SELECT string_agg(reason, E'\n' ORDER BY reason), count(DISTINCT seed_id)
    INTO problems, rejected FROM (
        SELECT seed_id, 'Coordinate identity/UUID conflict: seed ' || seed_id || ', existing ' || target_id AS reason
        FROM dev16_coordinate_matches WHERE NOT same_address OR NOT same_name
        UNION ALL
        SELECT seed_id, 'Ambiguous coordinate targets for seed ' || seed_id
        FROM dev16_coordinate_matches GROUP BY seed_id HAVING count(*) > 1
        UNION ALL
        SELECT m.seed_id, 'Existing venue matches multiple coordinate mappings: ' || m.target_id
        FROM dev16_coordinate_matches m WHERE m.target_id IN (
            SELECT target_id FROM dev16_coordinate_matches GROUP BY target_id HAVING count(DISTINCT seed_id) > 1
        )
    ) conflicts;
    IF problems IS NOT NULL THEN
        RAISE EXCEPTION 'DEV16 coordinates aborted: filled=0, rejected=%; all coordinate writes rolled back.% %',
            rejected, E'\n', problems;
    END IF;
    SELECT count(*) INTO matched FROM dev16_coordinate_matches;
    SELECT count(*) - matched INTO missing FROM dev16_coordinate_source;
    FOR detail IN
        SELECT s.venue_id FROM dev16_coordinate_source s
        WHERE NOT EXISTS (SELECT 1 FROM dev16_coordinate_matches m WHERE m.seed_id = s.venue_id)
    LOOP
        RAISE NOTICE 'DEV16 coordinates skipped missing venue %; no venue created', detail.venue_id;
    END LOOP;
    FOR detail IN
        SELECT v.venue_id FROM venues v
        JOIN dev16_coordinate_matches m ON m.target_id = v.venue_id
        JOIN dev16_coordinate_source s ON s.venue_id = m.seed_id
        WHERE (v.latitude IS NOT NULL AND v.latitude <> s.latitude)
           OR (v.longitude IS NOT NULL AND v.longitude <> s.longitude)
    LOOP
        RAISE NOTICE 'DEV16 coordinates preserved existing values for venue %; supplied pair not applied', detail.venue_id;
    END LOOP;

    -- Fill compatible missing components only. Never combine a reference value
    -- with a conflicting existing component or overwrite any saved coordinate.
    UPDATE venues v
    SET latitude = coalesce(v.latitude, s.latitude), longitude = coalesce(v.longitude, s.longitude)
    FROM dev16_coordinate_source s JOIN dev16_coordinate_matches m ON m.seed_id = s.venue_id
    WHERE v.venue_id = m.target_id
      AND (v.latitude IS NULL OR v.longitude IS NULL)
      AND (v.latitude IS NULL OR v.latitude = s.latitude)
      AND (v.longitude IS NULL OR v.longitude = s.longitude);
    GET DIAGNOSTICS filled = ROW_COUNT;
    RAISE NOTICE 'DEV16 coordinates complete: filled=%, preserved=%, missing=%, rejected=0', filled, matched-filled, missing;
END $$;
