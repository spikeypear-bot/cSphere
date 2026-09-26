-- Follow-up to V13 (D21), all additive:
--
--   1. Per-field clarification questions (EC01): each flagged field gets its
--      own question, so the organiser sees it beside that field (EO26).
--   2. Field values captured on the timeline when clarification is asked and
--      when the organiser resubmits, so the coordinator sees exactly what
--      changed in response (EC02 "view organiser responses"). This is scoped
--      to the clarification loop only; it is not EO16's general change
--      history, which stays out of scope.
--   3. A coordinator can cancel their own pending venue booking request
--      (EC03: "after a request is Rejected or Cancelled, a new one may be
--      submitted" needs a way to cancel), and Venue Staff are told.
--
-- JSONB rather than new tables: both are read only as part of the one
-- timeline entry they belong to, never queried on their own. The table stays
-- append-only; ALTER TABLE adds columns and does not touch existing rows.

ALTER TABLE event_request_activity
    -- {"expectedAttendance": "Is 150 final?", ...} on clarification_requested.
    ADD COLUMN field_questions JSONB,
    -- The request's field values at the moment of this entry, on
    -- clarification_requested and clarification_responded. Comparing the two
    -- shows what the organiser changed.
    ADD COLUMN field_values JSONB;

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'venue_booking_cancelled';

ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled') AND venue_booking_id IS NOT NULL)
);

COMMENT ON COLUMN event_request_activity.field_values IS
    'V14: request field values when clarification was asked / answered; the pair shows what changed (EC02).';
