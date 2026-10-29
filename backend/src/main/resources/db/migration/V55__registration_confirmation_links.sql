-- AT03: explicit registration links for persistent and queued confirmations.
ALTER TABLE notifications ADD COLUMN registration_id uuid REFERENCES event_registrations(registration_id);
CREATE INDEX idx_notifications_registration ON notifications(registration_id) WHERE registration_id IS NOT NULL;

-- Match historical notices by exact reference, authenticated owner and event.
-- Never guess from event alone: an attendee may withdraw and register again.
UPDATE notifications n SET registration_id = r.registration_id
FROM event_registrations r
WHERE n.type::text = 'attendee_registration_confirmed'
  AND n.recipient_user_id = r.attendee_id AND n.event_id = r.event_id
  AND n.message = 'Your registration is confirmed. Reference: ' || r.reference;

UPDATE notification_outbox o SET payload = jsonb_set(o.payload, '{registration_id}', to_jsonb(r.registration_id))
FROM event_registrations r
WHERE o.payload->>'type' = 'attendee_registration_confirmed'
  AND o.payload->>'recipient_user_id' = r.attendee_id::text AND o.payload->>'event_id' = r.event_id::text
  AND o.payload->>'message' = 'Your registration is confirmed. Reference: ' || r.reference;
