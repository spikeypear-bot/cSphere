-- AT08: retain unknown historical withdrawal dates rather than inventing a backfill.
ALTER TABLE event_registrations ADD COLUMN withdrawn_at timestamptz;
