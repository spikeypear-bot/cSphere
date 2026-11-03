-- EO03/EC-NEW3: requirements do not create requests or reservations.
ALTER TABLE event_requests ADD COLUMN equipment_items jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE events ADD COLUMN equipment_items jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE event_requests ADD CONSTRAINT chk_request_equipment_items_array CHECK (jsonb_typeof(equipment_items) = 'array');
ALTER TABLE events ADD CONSTRAINT chk_event_equipment_items_array CHECK (jsonb_typeof(equipment_items) = 'array');
