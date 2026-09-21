-- A block with no end date runs until someone removes it.
ALTER TABLE equipment_status_periods ALTER COLUMN period_end DROP NOT NULL;

-- In the new model a unit is Available unless a block says otherwise,
-- so "Available" blocks from earlier testing are meaningless.
DELETE FROM equipment_status_periods WHERE status = 'AVAILABLE';