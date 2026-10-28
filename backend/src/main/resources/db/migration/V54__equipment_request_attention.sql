-- EC-NEW3 (D37): an approved change can invalidate an approved equipment
-- request's reservation window or its requirements text. 'attention' marks
-- that automatically, mirroring VenueBookingStatus.attention (VS17): set the
-- moment ChangeRequestService.approve() finds the request no longer fits,
-- cleared by a revision or by the coordinator marking it still suitable.
-- attention_reason says which of the two it was, for the coordinator.
ALTER TYPE equipment_request_status ADD VALUE IF NOT EXISTS 'attention';
ALTER TABLE equipment_requests ADD COLUMN attention_reason TEXT;
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'equipment_request_attention';
