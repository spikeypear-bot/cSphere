-- EC-NEW3 (D36): the coordinator can revise an event's equipment request
-- when an approved change means the equipment it asked for is now wrong, in
-- either direction. "Supersede and rebuild" (D36): the previous request is
-- never edited in place, so a later audit can still see what changed and
-- why; it is marked superseded and a brand-new request stands in its place.
-- 'superseded' is reachable only from 'processing' or 'approved', through a
-- revision — never a status TS01/TS06 queues or decides directly.
ALTER TYPE equipment_request_status ADD VALUE IF NOT EXISTS 'superseded';
