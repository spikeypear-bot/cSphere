-- AT09 AC2: the venues an event was last confirmed with, as attendees know
-- them ("1 Harbour Road; 2 Marina Way", distinct and sorted). When the event
-- is confirmed again with different venues, registered attendees are told
-- what changed. Null until the event is first confirmed.
ALTER TABLE events ADD COLUMN confirmed_venues text;

-- Events confirmed before this migration: their currently approved venues.
UPDATE events e SET confirmed_venues = v.addresses
FROM (
    SELECT b.event_id, string_agg(DISTINCT ve.venue_address, '; ' ORDER BY ve.venue_address) AS addresses
    FROM venue_bookings b JOIN venues ve ON ve.venue_id = b.venue_id
    WHERE b.status = 'approved'
    GROUP BY b.event_id
) v
WHERE v.event_id = e.event_id AND e.first_confirmed_at IS NOT NULL;

COMMENT ON COLUMN events.confirmed_venues IS
    'AT09: venue addresses at the latest confirmation, "; "-separated; compared on re-confirmation.';
