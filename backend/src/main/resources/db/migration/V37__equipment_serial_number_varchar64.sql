-- TS09: serial numbers are short, kind-specific codes (e.g. EPS-EBU04-7K2Q9M), not
-- free text. 64 characters covers every real-world format we seed or expect.
-- The composite foreign keys (equipment_id, serial_number) keep working: VARCHAR
-- lengths do not have to match between referencing and referenced columns.
ALTER TABLE equipment_logs           ALTER COLUMN serial_number TYPE VARCHAR(64);
ALTER TABLE equipment_status_periods ALTER COLUMN serial_number TYPE VARCHAR(64);
ALTER TABLE serialised_equipments    ALTER COLUMN serial_number TYPE VARCHAR(64);
