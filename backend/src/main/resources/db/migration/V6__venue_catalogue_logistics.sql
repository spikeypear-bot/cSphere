-- VS06A: required capacity, multiple supported layouts and operating information.
-- Stop before changing data if existing venues need manual correction. Do not
-- invent capacities/layouts or guess how legacy free-text layouts are delimited.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM venues
        WHERE venue_capacity IS NULL OR venue_capacity NOT BETWEEN 1 AND 50000
           OR venue_layout IS NULL OR venue_layout !~ '[^[:space:]]'
           OR operating_information IS NULL
           OR operating_information !~ '[^[:space:]]'
    ) THEN
        RAISE EXCEPTION 'V5: existing venues need capacity between 1 and 50000, nonblank venue_layout and operating_information before migration';
    END IF;
END $$;

-- Preserve each existing layout verbatim as one array element. Multiple layouts
-- in a legacy prose value can be classified explicitly through a later update.
ALTER TABLE venues RENAME COLUMN venue_layout TO supported_layouts;
ALTER TABLE venues
    ALTER COLUMN supported_layouts TYPE text[] USING ARRAY[supported_layouts],
    ALTER COLUMN supported_layouts SET NOT NULL,
    ALTER COLUMN venue_capacity SET NOT NULL,
    ADD CONSTRAINT chk_venue_capacity_range CHECK (venue_capacity BETWEEN 1 AND 50000),
    ADD CONSTRAINT chk_venue_layouts_nonempty CHECK (
        cardinality(supported_layouts) > 0
        AND array_ndims(supported_layouts) = 1
    ),
    ADD CONSTRAINT chk_venue_operating_information_nonblank CHECK (
        operating_information ~ '[^[:space:]]'
    );

-- Array CHECK expressions cannot contain a subquery; validate each element in
-- a small immutable helper, including NULL and whitespace-only entries.
CREATE FUNCTION venue_layouts_have_values(layouts text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM unnest(layouts) AS layout(value)
        WHERE value IS NULL OR value !~ '[^[:space:]]'
    );
$$;

ALTER TABLE venues ADD CONSTRAINT chk_venue_layout_values
    CHECK (venue_layouts_have_values(supported_layouts));

COMMENT ON COLUMN venues.supported_layouts IS
    'VS06A: one-dimensional nonempty text array of nonblank supported layouts; accepted layout vocabulary is validated by the application.';
COMMENT ON COLUMN venues.operating_information IS
    'VS06A: nonblank operating days/hours and relevant constraints, recorded as free text.';
