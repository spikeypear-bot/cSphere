CREATE TABLE venue_operating_hours (
    operating_hour_id UUID PRIMARY KEY,
    venue_id UUID NOT NULL REFERENCES venues(venue_id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    CHECK (close_time > open_time),
    UNIQUE (venue_id, day_of_week)
);
