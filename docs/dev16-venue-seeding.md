# DEV16 shared venue catalogue

**Coordinate follow-up:** V62 now maps the later supplied coordinate references
onto these existing rooms. The original JSON and V60/V61 stay unchanged. Details
and current verification are in [coordinate mapping](dev16-venue-coordinate-mapping.md).
The original seed review and execution report below describe V60/V61 specifically.

The user authorised Option A on 31 October 2026: install the supplied venues with
Flyway, as a narrow exception to the usual profile-guarded fixture policy. The
user also explicitly chose to retain all supplied values as shared demo data.
This is not a claim that venue characteristics are operator-verified.

## Source review and mapping

`backend/dev/seed/venues.json` is the unchanged 28,023-byte supplied JSON (SHA-256
`18534646cfc5e395b9ff61bf2fc0e2dd0dad0db2daf130000b90414bff93c868`). It contains
20 rooms/spaces and 129 opening-day intervals; no duplicate normalised name/address
pairs, invalid enum labels, malformed intervals or out-of-range numeric fields were
found. Capacities range from 40 to 6,000. Days are Monday=1 through Sunday=7 in
Singapore local time; omitted days are closed, and overnight intervals are unsupported.

All 20 records lack coordinates. They remain SQL NULL, with no geocoding or invented
values. The Arts House Chamber's empty accessibility array remains unrecorded,
distinct from explicit `none`. Several capacity notes explicitly describe test,
conservative or illustrative values (including EXPO, Sands and Suntec); notes and
values are preserved verbatim. Opening schedules, facilities and setup/turnaround
values are supplied demo data, not independently verified operational facts.

| Source field | Existing storage |
|---|---|
| venueName / venueAddress / venueCapacity | venue_name / venue_address / venue_capacity |
| supportedLayouts | supported_layouts text[]; existing application vocabulary |
| venueAccessibilities / venueFacilities | existing accessibilities[] / facilities[] enums |
| setupMinutes / turnaroundMinutes | setup_minutes / turnaround_minutes (0..10080) |
| additionalInformation | additional_information, unchanged |
| operatingHours | venue_operating_hours, one interval per day |
| derived schedule | operating_information, ordered `Monday 09:00–18:00; ...` |
| optional latitude / longitude | V60 nullable double precision columns, inclusive ±90 / ±180 |

The existing catalogue, search and detail API read these same venue rows. Coordinate
fields are additive read fields; coordinate editing and map UI are outside this
change. Venue CRUD inputs, booking approval, availability and scheduling rules stay
as before. No events, bookings, users or notifications are seeded.

## Migration application

V60 adds coordinates and database range checks, including rejection of NaN/infinity.
V61 installs the catalogue and hours. Remote refs were refreshed and all local and
remote branches checked before allocating these versions; the highest existing
version was V59. Recheck version availability before merging concurrent work.

After pulling/merging, start the backend normally. With Docker, rebuild the jar:

```powershell
docker compose up -d --build
```

Existing databases run pending migrations; new databases run the complete migration
chain. Flyway is enabled at startup and tracks V60/V61 in flyway_schema_history.
Neither Python nor the JSON/template is needed at runtime: V61 is self-contained
SQL packaged in the jar. A restart with successful history runs neither migration
again. Do not reset/recreate a database, remove migration history, run cleanup
scripts, or edit an applied migration. Each migration is its own PostgreSQL
transaction: a V61 failure rolls back the entire seed; a successful V60 remains.

The shared catalogue applies to every database/profile running these migrations;
it is not gated on the development profile. The fixture-policy exception is
intentional for this dataset only.

## Identity, preservation and conflicts

Venue UUIDs are UUIDv5 under namespace `16c40f49-ddc5-5c95-a3c9-d5240d4e9d28`, named
`venue:` plus compact JSON containing the normalised room name and full address.
Normalisation trims, folds case and collapses whitespace. Hours use UUIDv5 under
the venue UUID, named `day:<1..7>`. Capacities, hours, ordering and coordinates do
not determine identity. A shared street address or location never merges rooms.

Before inserting, V61 locks venues and hours against concurrent writes and checks
all identities. One existing normalised name+address match (even with another UUID)
is skipped, preserving all its fields, hours and relationships. An existing seed
UUID must also match the room name and address; a different room using that UUID
is a collision even when it shares the same street address.
Legacy records whose name defaulted to their full address are recognised by that
exact address. If such a record could represent several seed rooms, import stops
for human review rather than merging the rooms.
Missing hours on a skipped venue are not filled in. A UUID at a different address,
multiple existing matches, or a new hours UUID already owned by another record
aborts the seed and reports the conflicting IDs. Identity-changing edits to a
known seed UUID also require review if the seed is ever explicitly rerun; normal
restarts use Flyway history and do not rerun it. No ON CONFLICT DO NOTHING/UPDATE
silently masks collisions. Events and bookings are never modified.

Matching is exact after case/whitespace normalisation. Renamed rooms with unrelated
UUIDs, alternate spellings and alternate address representations cannot reliably
be identified automatically. Review those manually before rollout; do not infer
identity from a building address, postcode or coordinates alone.

Flyway logs per-room skip notices and a final `DEV16 seed complete: imported=N,
skipped=N, rejected=0`. A fresh catalogue reports **20 imported, 0 skipped, 0
rejected**. A conflict reports **0 imported**, matched count, rejected source count
and conflicting IDs; no partial import is committed. Fix the conflict through a
reviewed data correction and restart, without rewriting V61. These counts are
per database; the JSON cannot predict teammates' existing records.

## Authoring and verification

Before initial application only:

```powershell
python backend/dev/seed/generate_venues.py
python backend/dev/seed/generate_venues.py --check
python -m unittest discover -s backend/dev/seed -v
```

The generator uses only the Python standard library. It validates the entire source
before writing SQL, rejects unmapped fields, and embeds the source SHA-256. After
V61 has been applied anywhere, use `--check` for verification only; later data
changes require a new migration. Never regenerate V61 with changed data/template.

Java tests are tagged DEV16 with integration layer tags and happy, boundary,
conflict, failure and cross-cutting display names. No canonical DEV16 AC numbers
were supplied, so none are invented. `VenueSeedMigrationTest` uses disposable
schemas on real PostgreSQL to test all source fields, history/restart, duplicate
and UUID collisions, full rollback, shared locations, and complete preservation
of preexisting venue/event/booking/hour records. `VenueSeedApiTest` verifies every
source room through existing authenticated catalogue/detail endpoints and checks
that characteristic updates preserve saved coordinates. Python tests cover
strict source validation, deterministic identities, shared locations and SQL drift.

Run against an isolated PostgreSQL test database, using Java 25:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:55416/dev16_verify'
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
./backend/mvnw.cmd -f backend/pom.xml -B -ntp '-Dtest=VenueSeedMigrationTest,VenueSeedApiTest,VenueControllerTest,VenueServiceTest,VenuePersistenceTest,VenueMapperTest,VenueHoursServiceTest,VenueSearchServiceTest,DomainClassDiagramTest,EntityRelationshipDiagramTest,ClassDiagramGeneratorTest' test
python .github/scripts/traceability.py --check
```

See the implementation report below for executed results. Tests never reset the
application database. Generated diagrams are refreshed by the existing DEV15 tests.

## Changed files

| Files | Purpose |
|---|---|
| backend/dev/seed/venues.json | Original source, unchanged |
| backend/dev/seed/generate_venues.py, venue_seed.sql.template | Strict validation and reproducible SQL generation |
| backend/dev/seed/test_generate_venues.py, .gitignore | Generator tests and ignored Python bytecode cache |
| backend/src/main/resources/db/migration/V60__venue_coordinates.sql | Nullable coordinates and range constraints |
| backend/src/main/resources/db/migration/V61__seed_shared_venues.sql | Transactional shared catalogue and hours import |
| venue/entity/Venue.java, venue/dto/VenueDto.java | Persistence and additive read fields |
| venue/mapper/VenueMapper.java, venue/service/VenueService.java | Preserve coordinates through existing read mappings |
| venue/VenueSeedMigrationTest.java, venue/VenueSeedApiTest.java | DEV16 PostgreSQL and HTTP integration coverage |
| AI_Context.md, migration/SCHEMA.md, docs/venue-api.md, this guide | Authorised exception, schema/API contract and rollout instructions |
| docs/class-diagrams/{domain,full}.generated.puml, docs/er-diagrams/schema.generated.puml | Existing test-generated design documentation |

Java production paths above are relative to `backend/src/main/java/com/example/connect_sphere/`;
Java test paths are relative to `backend/src/test/java/com/example/connect_sphere/`.
No existing versioned SQL migration, frontend file, dependency or CI configuration
was changed. No commit or push was made.

## Executed implementation report — 31 October 2026

Final verification used Java 25.0.4, PostgreSQL 18.3, and a dedicated loopback
cluster on port 55416 with database `dev16_release_verify`. The application
database was not connected to or changed. The final migration files were held
fixed throughout this run; no Flyway history repair or database reset was used.

| Check | Result |
|---|---|
| Full backend `mvnw ... verify` | **1,855 tests; 0 failures, 0 errors, 0 skips** |
| New Java DEV16 coverage (included above) | 11 migration tests and 2 API tests passed |
| Python generator tests | **6 passed**, including malformed values, duplicate identities, boundaries and SQL drift |
| Generator `--check` | 20 venues, 129 hours; SQL matches original source/template |
| Original source SHA-256 | Unchanged |
| Traceability `--check` | 244 files, 79 stories, 0 untraced files, 0 High-priority stories without tests |
| Backend package and JaCoCo report | Built successfully |
| Existing DEV15 diagram generation | Passed; only coordinate fields added to diagrams |
| Whitespace / existing versioned migration diff | Clean / no existing SQL migration changed |
| Isolated schema cleanup | 0 remaining `dev16_test_*` schemas; dedicated server stopped |

| Verified scenario | Imported | Skipped | Rejected |
|---|---:|---:|---:|
| Empty fully migrated catalogue | 20 | 0 | 0 |
| One existing matching room with user edits, hours, event and booking | 19 | 1 | 0 |
| One legacy address-as-name match | 19 | 1 | 0 |
| Explicit repeat seed after successful import | 0 | 20 | 0 |
| Venue UUID collision (including another room at same address) | 0 | 0 committed | 1 |
| Operating-hour UUID collision | 0 | 0 committed | 1 |
| Ambiguous existing matches | 0 | 0 committed | 1 |
| One unnamed legacy record matching two rooms at a shared address | 0 | 0 committed | 2 |

Conflict cases assert unchanged database snapshots after rejection. The V59 upgrade
case compares every preexisting venue field (excluding only the newly added null
coordinate columns), every hour, event and booking field. All source capacities,
layouts, accessibility/facility arrays, durations, notes, hours and derived summaries
are compared against the JSON. Source rooms remain separate at shared addresses
and coordinates. A second Flyway startup executes zero migrations and preserves
all venue/hour identities. Malformed source fixtures are rejected before SQL is
generated; they were not part of the imported catalogue.

Local evidence: `logs/dev16-backend-final.log`, `logs/dev16-generator-tests.log`,
`logs/dev16-traceability.log`, and Maven's `backend/target/surefire-reports/`.
Logs and build outputs remain ignored. These are local verification results; CI
has not run because this work has not been committed or pushed.
