-- VS17: retain the catalogue values preceding a venue edit.
ALTER TABLE venue_booking_assessments ADD COLUMN previous_venue_snapshot jsonb;
