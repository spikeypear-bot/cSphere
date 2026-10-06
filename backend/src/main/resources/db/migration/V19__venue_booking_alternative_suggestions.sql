-- VS09: persist optional alternatives with a rejected booking without
-- creating a booking or altering the event's requirements.
ALTER TABLE venue_bookings
    ADD COLUMN alternative_venue_id UUID REFERENCES venues(venue_id),
    ADD COLUMN alternative_arrangement TEXT,
    ADD CONSTRAINT chk_venue_booking_alternative_venue_different
        CHECK (alternative_venue_id IS NULL OR alternative_venue_id <> venue_id),
    ADD CONSTRAINT chk_venue_booking_alternative_arrangement_length
        CHECK (alternative_arrangement IS NULL OR char_length(alternative_arrangement) <= 2000);

COMMENT ON COLUMN venue_bookings.alternative_venue_id IS
    'VS09: optional alternative venue suggested with a rejection; this does not create a booking.';
COMMENT ON COLUMN venue_bookings.alternative_arrangement IS
    'VS09: optional free-text arrangement suggested with a rejection.';
