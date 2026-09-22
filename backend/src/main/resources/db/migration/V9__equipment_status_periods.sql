CREATE TABLE IF NOT EXISTS equipment_status_periods (
    status_period_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    equipment_id     UUID NOT NULL,
    serial_number    VARCHAR(255) NOT NULL,
    status           equipment_status NOT NULL,
    period_start     TIMESTAMPTZ NOT NULL,
    period_end       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (equipment_id, serial_number)
        REFERENCES serialised_equipments(equipment_id, serial_number),
    CHECK (period_end > period_start)
);

CREATE INDEX IF NOT EXISTS idx_status_periods_lookup
    ON equipment_status_periods (period_start, period_end);