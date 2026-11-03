-- EC-NEW3 (D40): which request a reservation was made for. A revision's own
-- reservations can then be told apart from the ones it replaces: rejecting a
-- request releases only its own, and booking a revision may reuse the units
-- the event will give up at its old time. Null for reservations made before
-- V74; they belong to the event's approved request.
ALTER TABLE equipment_logs ADD COLUMN request_id UUID REFERENCES equipment_requests (request_id);
CREATE INDEX idx_equipment_logs_request ON equipment_logs (request_id);
