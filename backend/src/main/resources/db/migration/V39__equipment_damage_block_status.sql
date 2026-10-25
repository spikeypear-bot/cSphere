-- TS09: a damage report can ask for the damaged unit to be blocked as
-- Unavailable as well as Faulty (TS03's two block statuses). V38's
-- mark_faulty / faulty_marked become block_status / block_applied.

ALTER TABLE equipment_damage_reports
    ADD COLUMN block_status text CHECK (block_status IS NULL OR block_status IN ('FAULTY', 'UNAVAILABLE'));

UPDATE equipment_damage_reports SET block_status = 'FAULTY' WHERE mark_faulty;

-- "Was the requested block added when the report closed?" now covers either status.
ALTER TABLE equipment_damage_reports RENAME COLUMN faulty_marked TO block_applied;

-- Dropping mark_faulty also drops V38's check that tied faulty_marked to it.
ALTER TABLE equipment_damage_reports DROP COLUMN mark_faulty;

ALTER TABLE equipment_damage_reports
    ADD CONSTRAINT chk_damage_block_serialised CHECK (block_status IS NULL OR serial_number IS NOT NULL),
    ADD CONSTRAINT chk_damage_block_applied CHECK (block_applied = false OR block_status IS NOT NULL);
