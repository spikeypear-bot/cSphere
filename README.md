## Project context for AI tools

Read **`AI_Context.md`** at the repository root before making any change with an AI coding assistant (Claude, Copilot, Cursor, etc.) — it's the single source of truth for scope, roles, architecture, conventions, and current status. `CLAUDE.md` is only a pointer to it, kept because Claude Code specifically looks for that exact filename.

## SetUp

1) Ensure Docker desktop is on.

### Frontend (React.ts)
from root:
2) npm run install:frontend / npm run ci:frontend
3) npm run dev

### Backend/DB
from root:
4) docker compose up --build -d (To build the springboot image and up the containers)
note: building of springboot app may take quite long during the first build, subsequently it takes 30s
5) docker compose down -d (To down containers)

### Database (PostgreSQL, paired with Springboot's Flyway migration)

note: all db files are to be written on the backend/src/main/resources/db/migration in the form of VERSION__description.sql, for example 1__init-table.sql 

### Optional VS16 demo data (Venue Staff / Event Coordinator)

Use the [VS16 local seed and testing guide](docs/vs16-manual-testing.md) to try
Catalogue → venue → associated booking → shared booking details before the Coordinator
workflow exists. Manual scripts under `backend/dev/seed/` create their own demo venue,
event and pending booking and include guarded cleanup. They never run automatically.
Share scripts, not database dumps. Development seeds are separate from Flyway migrations.

### Optional VS-C3 demo data (time-limited venue holds)

Use the [VS-C3 seed and manual testing guide](docs/vsc3-manual-testing.md) for
create/release, live expiry, conversion, competing requests and retained history.
After rebuilding the backend, run from PowerShell:

```powershell
./backend/dev/seed/vsc3-seed.ps1 -LocalDev -ExpiryMinutes 3
```

This manual API seeder creates a new labelled local batch with future booking
dates and relative expiry deadlines. It prints staff/coordinator links and saves
a local manifest under `logs/vsc3/`; it never runs at startup or resets old demos.

### Tests

How we test, and how every test traces to a user story and AC: [docs/testing-strategy.md](docs/testing-strategy.md).
Story → test matrix (generated): [docs/test-traceability.md](docs/test-traceability.md).

Browser end-to-end journeys (Playwright) against a running backend: see [frontend/e2e/README.md](frontend/e2e/README.md)
(`npm run test:e2e` from `frontend/`).

Frontend (Vitest + React Testing Library):
```
npm run test          # from frontend/, or npm --prefix frontend run test from root
npm run test:watch    # watch mode
```

Backend (JUnit 5 + Mockito). A `backend-test` service in `docker-compose.yml` is
the easiest way to run these — it needs no local JDK:

```
docker compose run --rm backend-test              # run all tests
docker compose run --rm backend-test test -Dtest=EventRequestServiceTest         # a single test class
docker compose run --rm backend-test test -Dtest='EventRequestServiceTest#methodName'  # a single method
docker compose run --rm --no-deps backend-test test -Dtest=EventRequestServiceTest      # without starting the db dependency
docker compose run --rm backend-test clean test    # clean + all tests
```

Mockito lets a test mock out dependent layers (e.g. a `@Mock` repository field on
a service test, so the service layer is tested in isolation). JUnit's `@Test` +
AssertJ assertions check the resulting values/errors. Controller tests use
`@WebMvcTest` + `@MockitoBean` (a fake service registered in the Spring test
container) with `MockMvc` acting as the HTTP client. `ConnectSphereApplicationTests`
boots the full application against a real DB connection to confirm it compiles and
wires together — lazy initialisation is off for tests so everything actually loads.

If you have JDK 25 locally instead, `./mvnw test` from `backend/` works directly.
Without Docker's `backend-test` service, run it inside a plain container against
the `db` service: `docker compose up -d db`, then from `backend/`:
`docker run --rm -v "$PWD:/build" -w /build --network <project>_default -e SPRING_PROFILES_ACTIVE=dev -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-db:5432/csphere -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=root eclipse-temurin:25-jdk ./mvnw test`
(On Windows, if `mvnw` fails inside the container with `/bin/sh^M: bad interpreter`,
its line endings were re-saved as CRLF by your editor/checkout — run
`sed -i 's/\r$//' backend/mvnw` and it'll work again; `.gitattributes` at the
repo root should prevent this going forward.)

### Test Tags

Every backend test class has a JUnit `@Tag`:

- `unit` — no database; dependencies are mocked.
- `integration` — `@SpringBootTest` against the real database.
- Story ID (e.g. `EC07`, `TS02`) — on the integration tests written for that story.

```
docker compose run --rm backend-test test -Dgroups=unit
docker compose run --rm backend-test test -Dgroups=integration
docker compose run --rm backend-test test -Dgroups=EC07
```

Tag new test classes the same way. CI runs unit and integration tests as separate steps.

### Test Coverage

To run jacoco , 

```
docker compose run --rm backend-test clean verify
xdg-open backend/target/site/jacoco/index.html 
```

to view the htmls.

Shows each package-> classes
Red diamond shows not ran at all, Yellow some missing branches, Green is all ran.

Jacoco only show whats ran during tests, but not whats actually test, so check to make sure

To Check code coverage for the frontend,
run
```
npm run coverage:frontend
xdg-open frontend/coverage/index.html
```


## Development
### Frontend
1) Ensure the app is created in routers when going throught the different views
2) Shared UI primitives live in `frontend/src/components/ui/`; per-role screens live under `frontend/src/features/<role>/`
3) There is no real login yet — see `docs/decision-log.md` D6a: the main page is a "Login as [Role]" selector (`frontend/src/lib/session.tsx`)
### Backend
1)  Addition of dependencies for the project through pom.xml, configs are in applications .properties for the backend
2) Unit tests to be created for each features/functions when the time is right
### DB
1) All db tables to exist and created via migration files, do not auto create in the springboot, keep auto-ddl to validate.

## Venue unavailability (VS01)

Venue Staff can record unavailable periods from Venue Details, review affected bookings, and notify coordinators without cancelling events. Availability includes setup/turnaround buffers. Coordinators can request a replacement; approval preserves the original as a changed booking. See [VS01 API, workflow, and manual testing](docs/venue-unavailability-api.md).

For cancellation, rejection and superseded-reservation behavior, see the
[VS25 acceptance review and remaining limitations](docs/venue-reservation-release.md).

## Venue booking timing (VS-C2 / EC-C2)

Coordinators can request multiple venues, each with its own booking window, setup duration and turnaround duration. Occupied-time checks include both buffers; Venue Staff can review and edit saved durations. Advertised event times remain unchanged.

- [Booking API and request examples](docs/venue-booking-api.md)
- [Timing workflow, V27/V28 migrations and verification](docs/venue-booking-duration-snapshots.md)
- [Calendar behavior](docs/venue-calendar-api.md)

Incoming migrations through V26 are retained. Databases that used the superseded unpublished local V23/V24 timing migrations need separate migration-history reconciliation before running this branch; do not rewrite applied migrations or blindly repair checksums.
