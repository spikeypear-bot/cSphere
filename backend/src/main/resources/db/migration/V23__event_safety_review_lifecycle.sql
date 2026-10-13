-- EC-NEW2 (one consistent status), EC08 (send for safety review, confirm)
-- and EO04 (organiser's confirmed arrangements). All additive. Builds on
-- V22 (SO-C1–SO-C4: Safety Officer role, readiness checklist, reviews).
--
-- Week 7 Change #6: after venue and technical arrangements are complete the
-- Safety Officer runs an Operational Safety Check, and the event cannot be
-- confirmed until it is approved. That needs three new event stages between
-- Planning (stored as 'pending', see V12) and Confirmed.
--
-- ALTER TYPE ... ADD VALUE cannot be used in the transaction that adds it
-- (same restriction V5/V12/V13 note), so nothing below compares against the
-- new labels; the notification CHECK compares type::text instead.

ALTER TYPE event_status ADD VALUE IF NOT EXISTS 'awaiting_safety_review';
ALTER TYPE event_status ADD VALUE IF NOT EXISTS 'safety_changes_requested';
ALTER TYPE event_status ADD VALUE IF NOT EXISTS 'safety_approved';

ALTER TABLE events
    -- EO04: "safety check approved, and when" without the organiser reading
    -- the internal timeline. Set by the Safety Officer's decision (SO-C4).
    ADD COLUMN safety_approved_at TIMESTAMPTZ,
    -- EC08: the Safety Officer's latest feedback when changes are requested
    -- or the arrangement is rejected (SO-C5/SO-C6), shown to the coordinator.
    ADD COLUMN safety_feedback TEXT,
    -- EO04: first and latest confirmation, so a re-confirmed event can show
    -- an "Updated" marker and date.
    ADD COLUMN first_confirmed_at TIMESTAMPTZ,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN confirmed_by UUID REFERENCES users(user_id);

-- Events confirmed before this migration keep a confirmation date, so EO04
-- never shows a confirmed event with no date.
UPDATE events SET first_confirmed_at = now(), confirmed_at = now()
WHERE status IN ('confirmed', 'completed') AND confirmed_at IS NULL;

-- Three new notification types:
--   event_confirmed            Venue Staff / Technical Support involved in
--                              the event are told it is confirmed (EC08).
--   event_returned_to_planning the coordinator is told an event left safety
--                              review or confirmation, and why (EC08).
--   event_safety_approved      the coordinator is told the Safety Officer
--                              approved, so the event can be confirmed (EC08).
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_confirmed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_returned_to_planning';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'event_safety_approved';

ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable') AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
);

-- SO-C4 + Week 7 Change #6: besides approve and reject, the Safety Officer
-- may request changes. V22 allowed only 'approved' and 'rejected'.
ALTER TABLE event_safety_reviews DROP CONSTRAINT IF EXISTS event_safety_reviews_review_status_check;
ALTER TABLE event_safety_reviews ADD CONSTRAINT event_safety_reviews_review_status_check
    CHECK (review_status IN ('approved', 'rejected', 'changes_requested'));
