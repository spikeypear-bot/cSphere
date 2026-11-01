-- DEV16: unknown coordinates stay NULL; no geocoding or inferred locations.
ALTER TABLE venues
    ADD COLUMN latitude double precision,
    ADD COLUMN longitude double precision,
    ADD CONSTRAINT chk_venue_latitude CHECK (latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT chk_venue_longitude CHECK (longitude BETWEEN -180 AND 180);

COMMENT ON COLUMN venues.latitude IS 'Optional verified latitude in degrees, -90 through 90.';
COMMENT ON COLUMN venues.longitude IS 'Optional verified longitude in degrees, -180 through 180.';
