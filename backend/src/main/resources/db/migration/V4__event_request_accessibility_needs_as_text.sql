-- event_requests.accessibility_needs moves from the shared `accessibilities`
-- enum-array type to text[], for this table only.
--
-- Why: Hibernate 7.4.5 could not reliably bind a value into a *named*
-- Postgres enum array column. Two approaches were tried against a real
-- Postgres 18 instance, not just in theory:
--   1. @JdbcTypeCode(SqlTypes.ARRAY) (+/- @Enumerated(STRING)) — Postgres
--      rejected the resulting smallint[]/varchar[] bind parameter against
--      accessibilities[] ("column is of type accessibilities[] but
--      expression is of type ...").
--   2. A custom Hibernate UserType using Connection.createArrayOf(...) to
--      bind the real Postgres type name — this broke SessionFactory
--      bootstrap entirely (ClassCastException: CustomType cannot be cast to
--      BasicPluralType), because Hibernate 7's DDL/cast machinery expects
--      array-typed attributes to go through its own BasicPluralType, not an
--      arbitrary UserType.
--
-- text[] sidesteps both problems: Hibernate's default array handling binds a
-- plain text/varchar array without any special casting, and
-- @Enumerated(EnumType.STRING) already gives Java-level validity checking on
-- every value that goes in. venues.venue_accessibilities and
-- events.accessibility_needs are untouched and keep the real
-- `accessibilities` enum-array type — this migration only affects the one
-- column where a working ORM mapping was needed today.

ALTER TABLE event_requests
    ALTER COLUMN accessibility_needs TYPE text[]
    USING accessibility_needs::text[];

ALTER TABLE event_requests
    ALTER COLUMN accessibility_needs SET DEFAULT '{}';

COMMENT ON COLUMN event_requests.accessibility_needs IS
    'text[] (not the accessibilities enum-array type used elsewhere) — see V4 migration comment and docs/decision-log.md for why.';
