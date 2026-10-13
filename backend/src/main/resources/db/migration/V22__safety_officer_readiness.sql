-- SO-C1–SO-C4: Safety Officer event visibility, readiness checklist and approval.
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'safety_officer';

CREATE TABLE event_readiness_items (
    item_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
    item_key VARCHAR(80) NOT NULL,
    checked BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(2000) NOT NULL DEFAULT '',
    checked_by UUID REFERENCES users(user_id),
    checked_at TIMESTAMPTZ,
    updated_by UUID REFERENCES users(user_id),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_event_readiness_item UNIQUE (event_id, item_key)
);

CREATE TABLE event_safety_reviews (
    event_id UUID PRIMARY KEY REFERENCES events(event_id) ON DELETE CASCADE,
    review_status VARCHAR(16) NOT NULL CHECK (review_status IN ('approved', 'rejected')),
    note VARCHAR(2000) NOT NULL DEFAULT '',
    reviewed_by UUID NOT NULL REFERENCES users(user_id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
