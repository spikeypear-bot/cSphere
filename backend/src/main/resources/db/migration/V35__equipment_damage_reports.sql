-- TS09: Technical Support report damaged equipment, decide what happens to
-- each affected event (covered by a spare, replaced, or short), and the
-- assigned Event Coordinator resolves a shortfall. V34 is taken by another
-- branch (serial numbers become VARCHAR(64)); the serial column here already
-- uses that length.

-- One report per damaged serialised unit (serial_number set, quantity 1) or
-- per damaged quantity of a non-serialised kind (serial_number null).
-- Non-serialised quantities stop counting as available for the reported
-- period (AC5); serialised units are taken out of use through TS03's status
-- periods when mark_faulty is chosen (AC4).
CREATE TABLE equipment_damage_reports (
    report_id uuid PRIMARY KEY,
    equipment_id uuid NOT NULL REFERENCES equipments(equipment_id),
    serial_number varchar(64),
    quantity integer NOT NULL CHECK (quantity >= 1),
    description text NOT NULL CHECK (length(btrim(description)) BETWEEN 1 AND 2000),
    period_start timestamptz NOT NULL,
    period_end timestamptz CHECK (period_end IS NULL OR period_end > period_start),
    mark_faulty boolean NOT NULL DEFAULT false,
    faulty_marked boolean NOT NULL DEFAULT false,
    status text NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    reported_by uuid NOT NULL REFERENCES users(user_id),
    reported_at timestamptz NOT NULL,
    closed_at timestamptz,
    CHECK (serial_number IS NULL OR quantity = 1),
    CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CHECK (faulty_marked = false OR (mark_faulty AND serial_number IS NOT NULL)),
    FOREIGN KEY (equipment_id, serial_number) REFERENCES serialised_equipments(equipment_id, serial_number)
);

CREATE INDEX idx_damage_reports_equipment ON equipment_damage_reports(equipment_id, period_start);

-- One row per event the damage affects. outcome is null until Technical
-- Support decides it. shortfall is how many units the event is now short of
-- what its approved requests asked for; the coordinator resolves it with a
-- note, or by an additional equipment request that Technical Support approves.
CREATE TABLE equipment_damage_outcomes (
    outcome_id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES equipment_damage_reports(report_id),
    event_id uuid NOT NULL REFERENCES events(event_id),
    outcome text CHECK (outcome IS NULL OR outcome IN ('COVERED', 'REPLACED', 'SHORT')),
    shortfall integer NOT NULL DEFAULT 0 CHECK (shortfall >= 0),
    reduced_by integer NOT NULL DEFAULT 0 CHECK (reduced_by >= 0),
    replacement_serial_number varchar(64),
    decided_by uuid REFERENCES users(user_id),
    decided_at timestamptz,
    additional_request_id uuid REFERENCES equipment_requests(request_id),
    resolution text CHECK (resolution IS NULL OR resolution IN ('NOTE', 'ADDITIONAL_REQUEST')),
    resolution_note text CHECK (resolution_note IS NULL OR length(btrim(resolution_note)) BETWEEN 1 AND 2000),
    resolved_by uuid REFERENCES users(user_id),
    resolved_at timestamptz,
    UNIQUE (report_id, event_id),
    CHECK ((outcome IS NULL) = (decided_at IS NULL)),
    CHECK ((outcome = 'SHORT') = (shortfall > 0)),
    CHECK (outcome IS NOT NULL OR shortfall = 0),
    CHECK (outcome IS DISTINCT FROM 'REPLACED' OR replacement_serial_number IS NOT NULL),
    CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    CHECK (resolution IS NULL OR outcome = 'SHORT'),
    CHECK (resolution IS DISTINCT FROM 'NOTE' OR (resolution_note IS NOT NULL AND resolved_by IS NOT NULL))
);

CREATE INDEX idx_damage_outcomes_event ON equipment_damage_outcomes(event_id);

-- An additional request answers exactly one shortfall.
CREATE UNIQUE INDEX idx_damage_outcomes_additional_request
    ON equipment_damage_outcomes(additional_request_id)
    WHERE additional_request_id IS NOT NULL;

-- equipment_damage_outcome: to the event's assigned coordinator (AC16).
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'equipment_damage_outcome';

-- Recreated from V33's version with the TS09 branch added.
ALTER TABLE notifications DROP CONSTRAINT chk_notification_shape;
ALTER TABLE notifications ADD CONSTRAINT chk_notification_shape CHECK (
    (type::text = 'status_change' AND new_status IS NOT NULL)
    OR (type::text = 'coordinator_assignment' AND coordinator_name IS NOT NULL AND is_reassignment IS NOT NULL)
    OR (type::text IN ('clarification_requested', 'clarification_responded') AND message IS NOT NULL)
    OR (type::text IN ('venue_booking_requested', 'venue_booking_cancelled', 'venue_unavailable',
        'venue_timing_changed', 'venue_timing_conflict', 'venue_hold_expired', 'venue_hold_expiring')
        AND venue_booking_id IS NOT NULL)
    OR (type::text IN ('event_confirmed', 'event_returned_to_planning', 'event_safety_approved') AND event_id IS NOT NULL)
    OR (type::text IN ('event_reassignment_requested', 'event_reassignment_approved',
        'event_reassignment_declined') AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text IN ('event_cancellation_requested', 'event_cancellation_approved',
        'event_cancellation_declined', 'event_cancelled') AND event_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text IN ('change_request_submitted', 'change_request_withdrawn', 'change_request_decided')
        AND event_request_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text = 'request_cancelled' AND event_request_id IS NOT NULL AND message IS NOT NULL)
    OR (type::text = 'equipment_damage_outcome' AND event_id IS NOT NULL AND message IS NOT NULL)
);
