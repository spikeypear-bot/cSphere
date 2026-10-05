-- Rename in place: existing committed bookings retain their IDs and all other data.
-- Event status is a separate enum and remains unchanged.
ALTER TYPE venue_booking_status RENAME VALUE 'confirmed' TO 'approved';
