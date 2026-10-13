-- SO-C5 / EC08 fix: V22 created event_safety_reviews.review_status as
-- VARCHAR(16) for 'approved' / 'rejected'. V23 allowed 'changes_requested'
-- in the CHECK but did not widen the column, and that value is 17
-- characters, so every "Request changes" decision failed with
-- "value too long for type character varying(16)" (a 500 to the Safety
-- Officer). Widening keeps the CHECK as the only list of allowed values.

ALTER TABLE event_safety_reviews ALTER COLUMN review_status TYPE VARCHAR(32);
