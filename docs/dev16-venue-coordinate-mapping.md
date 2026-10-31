# DEV16 coordinates for existing venues

The user asked to map two supplied coordinate reference files onto the existing
venue catalogue on 31 October 2026 and explicitly asked to ignore source URLs.
Both files contain 20 entries. Every name and full address matches the original
`backend/dev/seed/venues.json`, all coordinate pairs agree across both files, all
values are finite and in range, and the full reference payload changes no original
venue characteristic. No address, capacity, facility, accessibility, layout, note,
duration or operating schedule needs replacement.

Inputs reviewed:

- `ConnectSphere_20_Venue_Coordinates_Verified_Reference.json`
- `ConnectSphere_20_Venue_Payloads_With_Coordinates_REFERENCE_ONLY.json`

The supplied coordinate values are used directly. Their URLs were not visited or
copied into the repo. `backend/dev/seed/venue_coordinates.json` is the cleaned
mapping source: stable venue UUID, original name/address, latitude, longitude,
location granularity and verification note. The full coordinate-bearing payload
is not used to replace the existing venues or original source JSON.

## Precision and room identity

The 20 rooms use 13 distinct points. Several rooms share building coordinates;
each retains its own venue UUID and booking identity. These are map marker
locations with the precision stated in the input, not inferred floor plans.

EXPO Hall 1's point is explicitly the **EXPO complex** rather than a measured Hall 1
location. This is retained as `complex-only` with the supplied note. Victoria
Concert Hall uses its supplied hall-specific point while retaining the existing
9 Empress Place mailing address; the reference's note about the individual
11 Empress Place listing is retained as metadata. Neither note changes the stored
venue address or its public Additional information.

## V62 application and preservation

`V62__map_existing_venue_coordinates.sql` is a new self-contained SQL migration.
V60, V61 and `venues.json` are unchanged, including their original hashes. Remote
refs were refreshed and all branches checked before selecting V62; tracked
branches were at V59 or below and the local working tree already contains V60/V61.

Start the backend normally after pulling/merging (Docker needs
`docker compose up -d --build`). Existing databases apply pending V62; new databases
apply V60/V61 then V62. Flyway reads only packaged SQL, with no runtime dependency
on the JSON, Python or external geocoding. Successful history makes subsequent
backend restarts a no-op for the mapping. This change was tested only against
isolated databases; it does not automatically start your normal local backend.

V62 locks venue writes for its transaction and matches the deterministic UUID or
the exact normalised name+address. It also recognises legacy records whose name
defaulted to their address, including preexisting rooms preserved by V61 under
another UUID. Names and addresses are trimmed, case-folded and whitespace-collapsed;
coordinates, postcodes and building addresses alone never identify a room.

- A missing pair is filled from the mapping.
- A missing component is filled only when the saved component equals the reference.
- Complete pairs, including pairs differing from the reference, are preserved.
- Incompatible partial pairs are preserved as-is; no mixed pair is manufactured.
- Missing/deleted rooms are reported and skipped; no venue is created.
- Ambiguous targets or UUID/identity collisions abort all coordinate updates.

The only persistent writes are to latitude and longitude. Other venue columns,
IDs, operating hours, events, bookings and relationships are never rewritten.
Per-room preservation/missing notices and a final `DEV16 coordinates complete:
filled=N, preserved=N, missing=N, rejected=0` report the outcome. A rejected identity
reports `filled=0` and rejected source count; PostgreSQL rolls back the whole mapping.

## Authoring and tests

```powershell
python backend/dev/seed/generate_venue_coordinates.py --check
python backend/dev/seed/generate_venues.py --check
python -m unittest discover -s backend/dev/seed -v
```

The mapping generator validates all 20 identities against the unchanged original
source, rejects malformed/missing/duplicate coordinates and identities, and
embeds the cleaned mapping's SHA-256 in V62. Before first application only,
`python backend/dev/seed/generate_venue_coordinates.py` constructs the SQL from
the mapping and `venue_coordinates.sql.template`. Once V62 is applied, use
`--check` only; later corrections require another reviewed versioned migration.

`VenueCoordinateMigrationTest` (DEV16, integration) covers all coordinate values,
unchanged venue/event/booking/hour snapshots, shared points with separate room IDs,
saved/partial pairs, retained legacy UUIDs, missing venues, atomic collision failure,
invalid staged coordinates, Flyway history and explicit repeat execution.
`VenueSeedApiTest` retains all previous payload/hour checks and now asserts the
mapped coordinates on the existing authenticated catalogue/detail endpoints.
The V61 tests still independently verify null coordinates before V62.

Python tests cover mapping completeness, source/SQL agreement, precision notes,
URL omission, stable identities, invalid values and duplicate/missing mappings.
All tests use DEV16 story labels; no acceptance-criterion numbers were invented.

Changed files for this follow-up: the cleaned mapping JSON, its generator/template
and Python tests, V62, the new Java migration test, the existing DEV16 API test,
this guide, the seed/API guides and the DEV16 note in `AI_Context.md`. No production
Java, frontend, dependency or CI configuration changed.

## Executed verification — 31 October 2026

Final verification used Java 25.0.4 and PostgreSQL 18.3 in the dedicated
`dev16_coordinates_verify` database on loopback port 55416. The normal application
database was not connected to or changed. No existing database was reset, no
Flyway history was repaired, and no migration from V60/V61 was regenerated.

| Check | Result |
|---|---|
| Full backend `mvnw -f backend/pom.xml -B -ntp verify` | **1,862 tests; 0 failures, 0 errors, 0 skips** |
| New coordinate migration tests (included above) | **7 passed** |
| Existing seeded catalogue/detail API tests | Passed with all source fields/hours and exact supplied coordinate pairs |
| Python generator tests | **10 passed** (4 coordinate tests plus 6 original seed tests) |
| Both generator `--check` commands | Source/template/SQL agree |
| Traceability | 245 files, 79 stories, 0 untraced files, 0 High-priority stories without tests |
| Packaged backend and coverage report | Built successfully |
| V60/V61 history | Successful and unchanged |
| V62 history | Successful |
| Isolated coordinate test schema cleanup | 0 remaining schemas |
| Whitespace | Clean |

Observed mapping counts:

| Scenario | Filled | Preserved | Missing | Rejected |
|---|---:|---:|---:|---:|
| Standard V61 catalogue with null coordinates | 20 | 0 | 0 | 0 |
| Legacy room under its existing alternative UUID | 20 | 0 | 0 | 0 |
| Saved complete pair and incompatible partial pair, with compatible partial pairs elsewhere | 18 | 2 | 0 | 0 |
| One deleted room | 19 | 0 | 1 | 0 |
| Explicit repeat mapping after success | 0 | 20 | 0 | 0 |
| UUID collision or ambiguous target | 0 | All prior values unchanged | 0 | 1 |

The original JSON SHA-256 remains
`18534646cfc5e395b9ff61bf2fc0e2dd0dad0db2daf130000b90414bff93c868`;
V60 remains `7daa3909e7fd5fccb49f62e43bf917e3270336046a06f5e1fc7ecdfbb76c30e7`;
V61 remains `928cdfff1d071c6406826c67a4b1b6ffdb39b77004a26dccb613d4f94993c82c`.

Local evidence: `logs/dev16-coordinate-backend-verify.log`,
`logs/dev16-coordinate-generator-tests.log`, `logs/dev16-coordinate-traceability.log`
and `backend/target/surefire-reports/`. Build outputs and logs remain ignored.
The dedicated test server was stopped. No commit or push was made; these are local
verification results and CI has not run for this work.
