-- VS13: operational issues reported against a venue.
-- The affected period is optional, but start and end must be supplied together.
CREATE TABLE venue_operational_issues (
    issue_id UUID PRIMARY KEY,
    venue_id UUID NOT NULL REFERENCES venues(venue_id),
    description TEXT NOT NULL,
    affected_from TIMESTAMPTZ,
    affected_until TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_operational_issue_description CHECK (
        description ~ '[^[:space:]]' AND char_length(description) <= 2000
    ),
    CONSTRAINT chk_operational_issue_period_shape CHECK (
        (affected_from IS NULL AND affected_until IS NULL)
        OR (affected_from IS NOT NULL AND affected_until IS NOT NULL AND affected_until > affected_from)
    )
);

CREATE INDEX idx_venue_operational_issues_venue_period
    ON venue_operational_issues (venue_id, affected_from, affected_until);

COMMENT ON TABLE venue_operational_issues IS
    'VS13: operational issues affecting a venue; reporting does not alter bookings.';
