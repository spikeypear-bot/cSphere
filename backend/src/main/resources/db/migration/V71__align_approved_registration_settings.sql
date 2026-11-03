-- EO03/EC-NEW3: older capacity/period endpoints updated Event only.
-- Preserve Event as the approved registration source of truth and align the
-- originating request before future full-detail proposals are captured.
UPDATE event_requests request
SET registration_needs = event.registration_needs,
    registration_capacity = event.registration_capacity,
    registration_opens_at = event.registration_opens_at,
    registration_closes_at = event.registration_closes_at
FROM events event
WHERE request.event_id = event.event_id
  AND request.status::text = 'approved'
  AND (request.registration_needs, request.registration_capacity,
       request.registration_opens_at, request.registration_closes_at)
      IS DISTINCT FROM
      (event.registration_needs, event.registration_capacity,
       event.registration_opens_at, event.registration_closes_at);
