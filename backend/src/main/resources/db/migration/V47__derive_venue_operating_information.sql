-- Keep existing entity-based readers consistent with structured schedules.
UPDATE venues v
SET operating_information = schedule.summary
FROM (
    SELECT venue_id,
           string_agg(
               (ARRAY['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'])[day_of_week]
               || ' ' || to_char(open_time, 'HH24:MI') || '–' || to_char(close_time, 'HH24:MI'),
               '; ' ORDER BY day_of_week) AS summary
    FROM venue_operating_hours
    GROUP BY venue_id
) schedule
WHERE v.venue_id = schedule.venue_id;
