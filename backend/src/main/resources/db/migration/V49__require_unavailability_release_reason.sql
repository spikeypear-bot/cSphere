-- SQL CHECK treats NULL as unknown; a released period must always retain its reason.
ALTER TABLE venue_unavailability ADD CONSTRAINT chk_unavailability_release_reason_present
    CHECK (released_at IS NULL OR release_reason IS NOT NULL);
