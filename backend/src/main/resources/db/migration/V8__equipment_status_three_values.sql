-- Replace the 5-value equipment_status enum with the 3 values TS03 needs.
-- Mapping: available -> AVAILABLE, damaged -> FAULTY, everything else -> UNAVAILABLE

ALTER TYPE equipment_status RENAME TO equipment_status_old;

CREATE TYPE equipment_status AS ENUM ('AVAILABLE', 'FAULTY', 'UNAVAILABLE');

ALTER TABLE serialised_equipments
    ALTER COLUMN status TYPE equipment_status
    USING (
        CASE status::text
            WHEN 'available' THEN 'AVAILABLE'
            WHEN 'damaged'   THEN 'FAULTY'
            ELSE 'UNAVAILABLE'
        END
    )::equipment_status;

DROP TYPE equipment_status_old;