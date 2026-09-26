-- EC01 (request clarification), EC02 (review an event request), EO26
-- (organiser responds and resubmits) and EC03 (submit a venue booking
-- request). Four changes, all additive:
--
--   1. A 'clarification_required' event-request status (Week 4 'Event Review
--      and Approval': coordinators "request clarification or amendments").
--   2. event_request_activity: an append-only timeline of what happened to a
--      request and who did it, from submission to venue booking. It is the
--      audit trail EC01/EC02's ACs ask for ("view all clarification messages,
--      organiser responses and status history", "earlier entries are never
--      edited or overwritten") and the first slice of DEV07. It is not a chat:
--      every row is written by a workflow action, in the same transaction as
--      the status change it describes.
--   3. Three new notification types, plus the two columns they need.
--   4. Who submitted a venue booking request, when, and the coordinator's
--      justification when a venue does not fully meet the event's needs.
--
-- ALTER TYPE ... ADD VALUE cannot be *used* in the transaction that adds it
-- (same restriction V5/V12 note), so nothing below compares against the new
-- enum labels directly. The notification CHECK compares type::text instead.

ALTER TYPE event_request_status ADD VALUE IF NOT EXISTS 'clarification_required';

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'clarification_requested';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'clarification_responded';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_booking_requested';

-- ---- 2. Request timeline ------------------------------------------------

CREATE TABLE event_request_activity (
    activity_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES event_requests(request_id),
    -- Set once the request has become an event (approval onwards), so the
    -- timeline carries on past approval into venue planning.
    event_id UUID REFERENCES events(event_id),
    -- Text rather than a Postgres enum: adding a new kind of entry (a Venue
    -- Staff rejection reason, a Technical Support issue report, ...) is then a
    -- Java enum constant, not a migration. ActivityType in Java is the
    -- whitelist.
    activity_type VARCHAR(50) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(user_id),
    actor_role VARCHAR(20) NOT NULL,
    -- Snapshot, like notifications.coordinator_name: the timeline shows who
    -- acted as they were at the time, even if the account is renamed later.
    actor_name VARCHAR(255) NOT NULL,
    message TEXT,
    flagged_fields TEXT[] NOT NULL DEFAULT '{}',
    from_status VARCHAR(50),
    to_status VARCHAR(50),
    -- Which roles may see this entry (e.g. {ec,eo}). Filtered in the
    -- repository query, never only in the UI, so an Organiser's API call
    -- cannot return an internal-only entry.
    audience_roles TEXT[] NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_activity_audience_nonempty CHECK (cardinality(audience_roles) > 0),
    CONSTRAINT chk_activity_message_length CHECK (message IS NULL OR char_length(message) <= 2000)
);

-- Every read is "this request's entries, in order".
CREATE INDEX idx_activity_request ON event_request_activity (request_id, occurred_at);

-- Append-only, enforced by the database rather than by convention: "every
-- clarification and organiser response is retained in order and cannot be
-- edited or deleted" (EC01) has to hold even for a buggy service or a
-- hand-typed SQL session. The only way to remove history is to drop the
-- trigger, which is a migration and therefore reviewed.
CREATE FUNCTION forbid_activity_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'event_request_activity is append-only; % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_activity_append_only
    BEFORE UPDATE OR DELETE ON event_request_activity
    FOR EACH ROW EXECUTE FUNCTION forbid_activity_mutation();

-- ---- 3. Notifications ----------------------------------------------------

ALTER TABLE notifications
    -- The clarification message / organiser response shown in the
    -- notification itself, so the recipient sees what is being asked.
    ADD COLUMN message TEXT,
    -- venue_booking_requested links Venue Staff straight to the booking.
    -- No foreign key, on purpose: a notification is a historical record
    -- (see V12), and an FK would make any booking that was ever notified
    -- about undeletable — which breaks VenueBookingReadTest's own
    -- DELETE FROM venue_bookings isolation step on a used dev database.
    ADD COLUMN venue_booking_id UUID;

ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text = 'venue_booking_requested' AND venue_booking_id IS NOT NULL)
);

-- ---- 4. Venue booking requests (EC03) --------------------------------------

-- Nullable: rows created before EC03 (VS02/VS16 fixtures) have neither.
-- The booking's time window is deliberately NOT copied here: it is the
-- event's own start/end, so there is one source of truth for "when".
ALTER TABLE venue_bookings
    ADD COLUMN submitted_by UUID REFERENCES users(user_id),
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN suitability_note TEXT;

-- EC03's overlap check: confirmed bookings of one venue.
CREATE INDEX idx_venue_bookings_venue_status ON venue_bookings (venue_id, status);
CREATE INDEX idx_venue_bookings_event ON venue_bookings (event_id);

COMMENT ON TABLE event_request_activity IS
    'EC01/EC02/EO26/EC03: append-only request timeline (who did what, when). First slice of DEV07.';
COMMENT ON COLUMN venue_bookings.suitability_note IS
    'EC03: coordinator''s justification when the venue lacks a requested accessibility feature.';
