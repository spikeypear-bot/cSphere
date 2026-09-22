# ConnectSphere — Project Context for Claude Code

## Purpose

ConnectSphere is an Event Planning and Venue Booking System for ConnectSphere Event Services, a regional Southeast-Asian event-planning and venue-services organisation. It replaces fragmented coordination across email, spreadsheets, calendars, messaging apps, and manual documents with a single platform for planning and managing events, venues, equipment, and the people involved — Event Organisers, Event Coordinators, Venue Staff, Technical Support Staff, and Attendees. This is an SMU IS212 Software Project Management team deliverable: build a working, demonstrable, testable Release 1 that implements a customer-approved set of core features end-to-end, run under a real scrum process — not the broadest possible interpretation of the customer briefing.

## Sources of Truth (read in this order)

1. `docs/product-context.md` — detailed requirements, roles, workflows, business rules, data model, NFRs, traceability.
2. `docs/decision-log.md` — settled architecture/process decisions vs. items still awaiting team confirmation.
3. `docs/implementation-roadmap.md` — the sequenced build plan and current slice.
4. **The live product backlog — Google Sheet** (canonical, edited live by the whole team):
   `https://docs.google.com/spreadsheets/d/19VlaC36ed5ABgLAl9t8qhi5Eh4zO7V2JjtxRyCrNPjI/edit?usp=sharing`
   Sheets: Visual Story Map, User Stories (master — 114 rows as of 2026-09-12), Prioritised User Stories, Estimation, Task Board, Sprint Backlog 1–4, Full Backlog Table, Read Me, Lists. This is the authoritative source for user stories, scope calls, priorities, points, sprint assignment, and per-person `Assigned To`. See "Product Backlog Spreadsheet" below for how to read/edit it safely.
   The repository's own copy, `Materials/Project/v2 ConnectSphereProductBacklog (4).xlsx`, was a snapshot of this sheet and is being retired by the team — if it still exists when you check, prefer the Google Sheet; if it's gone, that's expected, not a missing-file error.
5. `Materials/Project/Week 1 Customer Briefing.docx` and `Materials/Project/Week 4 Project Instructions.docx` — original customer requirements and the official Release 1 scope (20 core features) and grading rubric.
6. The current repository code, configuration, README, tests, schema/migrations, and CI files — always verify against these before assuming a capability exists.

If sources conflict, do not silently pick one — flag the conflict, name the affected feature, and ask.

Do not treat this file as proof that a specific framework, database, endpoint, UI, or feature already exists. Verify the repository first.

## Users

Application (product) roles — from the customer briefing, all VERIFIED:

- **Event Organiser** (external): client representative who creates/submits event requests and communicates with ConnectSphere.
- **Event Coordinator** (internal): assigned to coordinate an event; main internal point of contact.
- **Venue Staff** (internal): maintains venue records, decides venue availability/booking approval.
- **Technical Support Staff** (internal): maintains equipment records, decides equipment availability/reservation.
- **Attendee** (external): registers for and attends events where registration is enabled.

An **"Authenticated User"** cross-cutting capability (login, session, role-based access control) underlies all five roles — the backlog models this as its own set of stories because Week 4's "User Authorisation and Authentication" core feature applies identically to every role, not because it is a sixth application role.

**Not application roles** — these are backlog/process modelling categories used by the team's own scrum practice, not roles the software needs to authenticate or authorise:
- **Developer** — labels *Technical*-type backlog stories (architecture/C4 diagrams, DB schema, CI pipeline, RBAC-at-API, concurrency-safe booking, audit log, input validation, responsive UI, containerisation) that the course rubric explicitly grades (system design, code quality/CI, testing/traceability).
- **Scrum Master** / **Product Owner** — label *Process*-type backlog stories (sprint planning, daily scrum, retros, backlog ordering, DoD acceptance) that describe the team's own scrum ceremonies, not schedulable software increments. These carry no Points/Sprint value and are intentionally **Out of Scope** of the software product backlog.

**System Administrator**: not named in the briefing, the Week 4 core-feature list, or the backlog. Do not add one unless the backlog/team explicitly adds it.

## Release 1 Scope — the 20 Core Features (VERIFIED, Week 4 Project Instructions)

The customer has named exactly these 20 features as Release 1 core scope. Everything else in the wide Week 1 briefing is explicitly **not required** for Week 12 unless the backlog's "Within Scope, Included" column says otherwise. Ranked here by the backlog's own priority tally (High → Medium → Low, per `User Stories` sheet):

**High priority**
1. User Authorisation and Authentication (RBAC, login, session security)
2. Event Request Creation
3. Draft Event Requests
4. Event Review and Approval
5. Coordinator Assignment
6. Event Status Management
7. Venue Catalogue
8. Venue Search and Filtering
9. Venue Suitability Checking
10. Venue Booking Request
11. Venue Booking Approval
12. Booking Conflict Detection
13. Equipment Request Management
14. Equipment Availability Checking
15. Equipment Reservation
16. Attendee Registration
17. Event Change Requests

**Medium priority**
18. Event Information Management
19. Venue Availability Calendar
20. Notification System

Every one of the 114 rows in the `User Stories` sheet has been individually scoped against this list (`Within Scope, Included` / `Out of Scope` / `Added`) with a one-line rationale — treat that column, not this file, as the ongoing source of truth as the backlog evolves.

## Explicit Out-of-Scope / Deferred (VERIFIED, backlog `Out of Scope` rows)

Do not build these unless the backlog is updated to bring them in scope: event programme/multi-session recording, supporting-document attachments, in-app comments/discussion threads, tentative venue holds, venue setup/turnaround-time tracking as a distinct feature, Event-Coordinator "portfolio" browsing, rejection appeals, user guides, dashboards/role-based overview screens, most reporting/export features, attendance-vs-registration distinction, waiting lists, and technical-staff on-site assignment. Recurring/similar-event reuse and event categories are also out of the named 20 and not modelled.

## Functional & Non-Functional Requirements

Functional requirements are the 20 core features above, each with acceptance criteria on the `User Stories` sheet — treat AC text there as authoritative over any paraphrase here.

NFRs (VERIFIED, customer briefing §8, INFERRED into concrete stories on the backlog as DEV07/DEV08/DEV09/DEV10):
- **Performance**: common operations (view event, search venues, open calendar, check equipment, submit registration) should complete in a reasonable time — no numeric SLA given by the customer; do not invent one without confirming with the team.
- **Security**: role- and relationship-based access control; API-layer enforcement, not just UI hiding (DEV05).
- **Usability**: no extensive training required; responsive desktop + mobile (DEV09).
- **Reliability/consistency**: no contradictory information across event/venue/equipment/registration views.
- **Scalability**: designed for growth in events/users/clients/venues/equipment/registrations over ~3 years — no specific numeric target given.
- **Auditability**: what changed, who changed it, when (DEV07 — internal audit log). The current schema (`V2__init_tables.sql`) only captures `created_by` on `event_requests`; a general audit trail does not exist yet (see decision log).
- **Maintainability**: architecture should allow future rules/features without a full rebuild — this is the stated reason for keeping schema ownership in Flyway migrations and documenting C4 diagrams (DEV01, DEV12).

## Critical Business Rules & Validation Rules (VERIFIED — customer briefing + backlog ACs)

- A venue must **not** be treated as suitable when expected attendance exceeds its capacity, a required accessibility feature is absent, or a required facility is unavailable.
- **Booking conflict detection**: a confirmed venue booking must affect whether that venue is considered available for other events over the same period. The current schema has **no DB-level overlap constraint** on `venue_bookings` — this must be enforced in application logic (see `SCHEMA.md` gap #1); do not rely on the frontend alone as the customer briefing and DEV06 both call for a backend concurrency-safe check.
- **Equipment availability** = `equipment_qty` − sum of overlapping `equipment_logs.quantity` for the requested window (see the query documented in `SCHEMA.md`). Damaged/maintenance equipment must not be treated as available.
- **Draft → Submitted** transition: a draft may be saved with incomplete information; submission must be blocked while required fields are missing, and the system must tell the user what's missing (EO01/EO02 ACs).
- **Event status** must be a single, consistent value derived from the approved status model — never computed differently by different screens (EC-NEW2 AC, threaded-comment note on B16 of Sprint Backlog 3: keep automatic status transitions in acceptance criteria, not in the user-story statement itself).
- **RBAC / data scoping**: an Event Organiser may access only their own organisation's requests; an Event Coordinator only events assigned/authorised to them; Venue Staff only venue records/bookings they manage; Technical Support Staff only equipment/reservations relevant to them; an Attendee only their own registration and confirmed event info they're entitled to see. Enforce this server-side (API), not only via hidden UI — direct URL access or role/ownership tampering must not grant wider access (AU04 refined AC, threaded comment E22 on Sprint Backlog 1).
- **Registration** is bounded by a defined capacity and an open/close period; registrations beyond capacity or outside the period must be prevented or handled per an agreed rule (waiting list is out of scope for Release 1 unless the backlog changes).
- **Equipment status update** (Available/Faulty/Unavailable) never itself creates, cancels, or alters a reservation — these are separate concerns (threaded comment E21 on Sprint Backlog 1).
- **Interim access model (team decision 10/9/2026 — now being superseded on `feat/auth`, see D19)**: real account creation and credential-based login (`AU01A/B/C`, `AU02`, `AU03`, `AU05`) are deliberately deferred. For now, the main page offers a "Login as [Role]" button per role, which routes straight into that role's views with no credentials involved. `AU04` (only see/do what your role permits) and `AU06` (clear message on an unauthorised action) still apply, but their mechanism is the selected role, not a verified account — whether that role must also be enforced server-side during this phase is an open question (`docs/decision-log.md` Q2). Do not build real registration/login screens unless the team brings them back into the current slice.

## Data / Domain Model — VERIFIED from `backend/src/main/resources/db/migration/`

Authoritative reference: `backend/src/main/resources/db/migration/SCHEMA.md` (hand-written schema dictionary) and `V2__init_tables.sql`. Do not re-derive this from memory — read those files.

Entities: `users`, `venues`, `events`, `event_requests`, `equipments`, `serialised_equipments`, `equipment_requests`, `equipment_request_equipments`, `equipment_logs`, `venue_bookings`, `refresh_tokens` (added by `V7__refresh_tokens.sql` on `feat/auth`). Relationships and enum types (user_role, event_status, event_request_status, equipment_request_status, equipment_status, venue_booking_status, accessibilities, facilities) are documented in `SCHEMA.md` — read it for the full ER diagram and column-level detail before writing any entity, repository, or migration.

**Known, team-acknowledged gaps in the current schema** (from `SCHEMA.md` §5 — PROPOSED to resolve, not yet decided): no DB-level venue-booking overlap protection; `request_type`/`equipment_type` single-char codes undocumented; `event_requests` allows null start/end while `events` requires them (the approval path must handle this); overlap between `equipment_requirements` (free text) and `equipment_requests.technical_requirement`; no audit trail beyond `created_by`; no equipment return/check-in flag. Confirm resolutions with the team before assuming any of these are settled — see `docs/decision-log.md`.

`mock` / `mock_references` (`V1__mock_data.sql`) are scaffolding to demonstrate the MVC layering convention (see `backend/.../mock/*`) — not domain entities. Delete only once real entities exist and after team agreement.

## Existing Architecture & Technology (VERIFIED by repository inspection — 2026-09-12)

- **Backend**: Spring Boot **4.1.1**, Java **25**, Maven (`backend/pom.xml`, wrapper at `backend/mvnw`). Web MVC + RestClient + Actuator + springdoc-openapi (Swagger UI likely at the default path). Persistence: Spring Data JDBC **and** JPA both present; Flyway (`flyway-database-postgresql`) owns the schema — `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.generate-ddl=false` (`application.properties`). Lombok for boilerplate, MapStruct for entity↔DTO mapping. One demo vertical slice exists (`mock` package: controller → service → repository → entity → DTO → mapper) purely to show the intended MVC convention — no real domain controllers/services exist yet.
- **Security** (built on `feat/auth`, 2026-09-21/22 — see D19 in `docs/decision-log.md`, **not yet team-ratified**): `spring-boot-starter-security` (**Spring Security 7.1.1**, Framework 7.0.9 via the Boot 4.1.1 BOM) plus `spring-boot-starter-security-oauth2-resource-server` — note the `security-` prefix; the shorter `spring-boot-starter-oauth2-resource-server` still resolves but its own POM marks it deprecated, and every tutorial uses the old name. **This matters more than anything else when searching for help: virtually every JWT/Spring Security tutorial online targets Boot 3.x / Security 6.x, and Security 7 removed the deprecated non-lambda DSL — `.and()` chaining, `http.csrf().disable()`, and `WebSecurityConfigurerAdapter` will not compile.** Verified 7.1.1 API differences that break tutorials: `DaoAuthenticationProvider` has **only** a `DaoAuthenticationProvider(UserDetailsService)` constructor (no no-arg ctor, no `setUserDetailsService`) while the encoder still goes in via `setPasswordEncoder`; `NimbusJwtEncoder.withSecretKey(key).build()` and `NimbusJwtDecoder.withSecretKey(key).build()` both exist and default to HS256, so the `ImmutableSecret` JWKSource route — which defaults to RS256 and needs an explicit `JwsHeader` — is unnecessary. Boot 4 also moved classes: `EndpointRequest` is now at `org.springframework.boot.security.autoconfigure.actuate.web.servlet`, `@WebMvcTest` at `org.springframework.boot.webmvc.test.autoconfigure`.
  **What exists now (stages 0–7 of D19 + D20, all live-verified):** stateless filter chain (`config/SecurityConfig` — no `HttpSession`, CSRF disabled for the token API, `/actuator/**` and `/api/auth/**` permitAll, role rules per D20); `config/AuthenticationConfig` (a `ProviderManager` over a `DaoAuthenticationProvider`); `config/JwtConfig` (HS256 encoder/decoder + a `JwtAuthenticationConverter`); `user/` slice (`User`, `UserRole`, `UserRepository`, `UserPrincipal`, `AppUserDetailsService`, `DevUserSeeder`, `TokenService`, `RefreshTokenService`); `POST /api/auth/login|refresh|logout`. Access tokens are 15-minute HS256 JWTs claiming `sub` (user UUID), `iss`, `iat`, `exp`, `username`, `role`, `organisation`; refresh tokens are opaque 256-bit values, SHA-256-hashed in `refresh_tokens`, single-use, rotating, family-revoked on reuse. **HTTP Basic has been removed** — log in and send a Bearer token; `curl -u` no longer works. `@EnableMethodSecurity` is on. `common/web/RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) put filter-chain rejections into the `ApiError` shape, and an `AccessDeniedException` handler on `ApiExceptionHandler` does the same for `@PreAuthorize` denials. Role rules for every endpoint live in `authorizeHttpRequests` (D20); `/api/event-requests/**` is additionally scoped by the token's `organisation` claim, **not** by the old `X-Organisation` header, which is no longer read. **Still missing:** the frontend — `apiClient` sends no `Authorization` header, so the React app is 401 against its own backend until a login page replaces D6a's role selector.
- **Database**: PostgreSQL **18**, run via `docker-compose.yml` (`db` service, `csphere` database). Migrations live at `backend/src/main/resources/db/migration/V{n}__description.sql`; **never** hand-edit an already-applied migration — Flyway checksums it and the next boot fails. Add `V3__*.sql` instead, or reset dev DB with `docker compose down -v`.
- **Frontend**: React **19.2**, Vite **8**, TypeScript **~6.0**, ESLint **10** + typescript-eslint. `frontend/src/App.tsx` is still the untouched Vite starter template — no real screens, no router, no state-management library, no styling library beyond plain CSS, and **no test framework** installed (no Jest/Vitest/React Testing Library) as of this writing.
- **No CI pipeline exists yet** (no `.github/workflows/` or equivalent) — confirmed absent from the repository.
- Git: `main` is the default branch; a `feature/event_request` branch exists but currently has zero commits ahead of `main`.

## Development Conventions (inferred from the codebase — confirm before deviating)

- Layering: Controller → Service → Repository → Entity, with a DTO + MapStruct mapper per entity, matching the `mock` package's structure and its inline comments.
- Lombok `@Getter @Setter` on entities rather than hand-written accessors.
- Postgres enum and array columns need `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` / `SqlTypes.ARRAY` (Hibernate 6.2+) — plain `@Enumerated(EnumType.STRING)` will fail against these columns (`SCHEMA.md` §6).
- Composite keys (e.g. `serialised_equipments`, `equipment_request_equipments`) use `@IdClass`/`@EmbeddedId`, matching the existing `MockReferenceId` pattern.
- Schema is owned by Flyway migrations, never by JPA auto-DDL — entities can be added one at a time; tables without an entity are simply ignored by `ddl-auto=validate`.
- Enum-array columns are queried with the `@>` containment operator to hit the existing GIN indexes (`idx_venue_accessibility`, `idx_venue_facility`) — `= ANY(...)` will not use them.
- **Database-owned column values** use Hibernate's `@Generated(event = EventType.INSERT)` (see `User.createdAt`), not a value assigned in Java. A Postgres `DEFAULT` only applies when the INSERT **omits** the column, and Hibernate names every mapped column by default — so an unset field is sent as an explicit `NULL`, which overrides the default and trips `NOT NULL`. `@Generated` makes Hibernate leave the column out and read the value back afterwards. Verified against a real insert, 2026-09-21. Application-assigned IDs (`UUID.randomUUID()` in the service/seeder) remain the convention — there is no `@GeneratedValue` anywhere.
- **Seed/fixture data belongs in a profile-guarded `CommandLineRunner`, never in a Flyway migration** (see `DevUserSeeder`). Migrations own *schema* and reference data the app cannot run without; environment-specific fixtures are neither, and migrations are immutable once applied — seeding accounts in a `V{n}` file means a new migration every time a password or user changes. A runner can also inject the real `PasswordEncoder` bean, so seeded hashes and login verification are guaranteed to use the same encoder (the classic "correct password always rejected" bug is encoding and verifying with different ones). `V1__mock_data.sql` is *not* precedent for the opposite — it is scaffolding slated for deletion.
- **Granted authorities are a bare string match.** Since HTTP Basic was removed, `JwtConfig`'s `JwtAuthenticationConverter` is the only thing that grants authorities to a request; it produces `"ROLE_" + role.toUpperCase()`. (`UserPrincipal.getAuthorities()` still produces the same shape, but it is now only reached during login, where the authorities are not used for any authorisation decision — keep the two in step anyway, since a future non-token path would depend on it.) `hasRole("VS")` compiles to an exact `String.equals` against `"ROLE_VS"`, and a `GrantedAuthority` is just a wrapped string with no registry or validation — so `ROLE_vs` and `ROLE_VS` are unrelated, and a mismatch surfaces only as a 403 that reads like a broken rule. Worse, `@WithMockUser(roles = "VS")` tests would still pass while real Bearer requests 403, because that annotation builds the authority itself rather than going through the converter. Change the two together or not at all. The `role` claim itself stays lowercase — that is the domain value the frontend reads; the `ROLE_`/uppercase shape is Spring Security convention and translating into it belongs in the converter.
- **Check every branch before claiming a migration version.** `git ls-tree -r --name-only <branch> | grep V[0-9]` across all branches, not just your own — `origin/main` and five feature branches already carry a `V7`. The team has hit a version collision once (D15) and `feat/auth` deliberately claimed a colliding `V7` a second time, to be renumbered on merge.
- **Object-oriented modelling rules (IS212 Week 5, "Communicating Design & Collaborating on Code"):** use inheritance only for a real "is-a" relationship that *also* needs polymorphism (a subclass overriding a method so the same call site behaves differently per type at runtime) — not for data-only variation (e.g. a role label), which belongs on one flat class as a field/enum instead. When an association between two classes carries its own meaningful data (dates, quantities, a status), give it its own class rather than bolting fields onto either side. Prefer a plain association over aggregation, and aggregation over composition, unless the stronger relationship is genuinely true — composition only when the child has no independent existence. Model only classes an actual story/AC needs. Keep class/sequence diagrams as versioned PlantUML/Mermaid text committed to the repo (see `docs/class-diagram.puml`), updated in the same PR as the code they describe, not a one-off exported image — see `docs/decision-log.md` D16 for how this was applied to the current domain model.

## Adding a new API endpoint — checklist (D19/D20)

Authentication and authorisation are in place; these are the steps that are easy
to forget, ordered by how quietly they fail.

**1. Add a role rule, or your endpoint is open to all five roles.**
`anyRequest().authenticated()` catches anything unlisted, so a new endpoint is
never *public* — but it is reachable by every signed-in account until a matcher
exists. Add a row to `docs/decision-log.md` D20, then the matcher in
`SecurityConfig.authorizeHttpRequests`. **This failure is silent:** no error, no
red test, the feature demos perfectly. The only thing that catches it is a
negative test (below).

**2. Matcher order decides the outcome.** First match wins and evaluation stops,
so a method-scoped rule must come *above* the path-wide rule it carves an
exception out of — e.g. `GET /api/venues/**` → `authenticated()` before
`/api/venues/**` → `hasAnyRole("EC","VS")`. Reverse them and reads become
write-role-only. `anyRequest()` must be last; Spring fails at startup otherwise.

**3. `hasRole` takes exactly one role** — `hasAnyRole("EC","VS")` for several.
Never write the `ROLE_` prefix yourself, and match the case: authorities are
`ROLE_VS`, so `hasRole("vs")` 403s everything.

**4. Never read identity or scope from the request.** No `X-Organisation`-style
header, no `userId` in a body or query parameter. Take it from
`@AuthenticationPrincipal Jwt jwt` and read the claim (see
`EventRequestController.organisationOf`). Anything the client can type, the
client can forge — this was a live cross-organisation leak on 2026-09-22.

**5. Role gating is not data scoping.** A matcher or `@PreAuthorize` answers
*whether* a caller may reach the endpoint. *Which rows* they get is a `WHERE`
clause in the repository. For a list there is no yes/no to make at all — the
answer is a smaller result set, and no annotation can express that.

**6. `@PreAuthorize` only where the rule needs a method argument or the
principal.** A plain role check belongs in the filter chain, which rejects
earlier and keeps every rule in one readable list. Never write the same rule in
both places: the URL layer wins, so a drifted pair leaves the annotation looking
load-bearing when it is not.

**7. Tests differ by test type, and the difference is not obvious:**

| Style | What security it loads | What you must do |
| --- | --- | --- |
| `@SpringBootTest @AutoConfigureMockMvc` | the real filter chain | `@WithMockUser(roles = "…")`, or `SecurityMockMvcRequestPostProcessors.jwt()` when the test needs claims |
| `@WebMvcTest` slice | Boot's **default** chain, not `SecurityConfig` | `@AutoConfigureMockMvc(addFilters = false)`; set `SecurityContextHolder` directly if the controller reads a principal |
| direct service call | none — the context is empty | `@WithMockUser` only if the method carries `@PreAuthorize` |

`jwt()` inside a slice with `addFilters = false` silently yields a **null**
principal: it saves through a `SecurityContextRepository` that only the filter
chain loads back. See `EventRequestControllerTest` for the working pattern.

**8. Write a negative test per boundary** — one call with a role that should not
have access, asserting 403 and that nothing was written. This is the only thing
that catches step 1 being skipped; `VenueControllerTest` has the pattern, and
`EventRequestScopingTest` has the cross-organisation equivalent.

**9. Frontend: call it through `apiClient`** (`get`/`post`/`put`/`del`). That is
what attaches the Bearer token, renews an expired one, replays the original
call, and ends a dead session. A raw `fetch` gets none of it and 401s forever —
which is exactly what happened to `equipmentStatusApi.ts`.

**10. Errors**: throw a domain exception and map it in `ApiExceptionHandler`.
Do not hand-roll 401/403 bodies — `RestAuthenticationEntryPoint` and
`RestAccessDeniedHandler` already produce the shared `ApiError` shape.

**A new migration needs a rebuilt image.** `docker-compose.yml` builds the
backend from its Dockerfile, which packages `src/` into a jar; Flyway reads
migrations from that jar, not from disk. Use `docker compose up -d --build
backend` — plain `up` reuses the old image and the migration silently never
runs. `down -v` is only needed when a migration that was already applied has
been edited or renumbered, not for a new one.

## Commands (VERIFIED only — do not invent others)

- Frontend install: `npm run install:frontend` (root) or `npm --prefix frontend install`; CI-style install: `npm run ci:frontend`.
- Frontend dev server: `npm run dev` (root, proxies to `vite` in `frontend/`).
- Frontend build: `cd frontend && npm run build` (`tsc -b && vite build`).
- Frontend lint: `cd frontend && npm run lint` (`eslint .`).
- **Getting a token** (`feat/auth` only): `curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"ec1","password":"123456"}'` returns `accessToken`, `refreshToken`, `expiresIn`, plus `username`/`role`/`organisation`. Then `curl -H "Authorization: Bearer <accessToken>" localhost:8080/api/venues`. `POST /api/auth/refresh` with `{"refreshToken":"..."}` rotates it (the old one dies, and re-presenting it revokes the whole family); `POST /api/auth/logout` with the same body returns 204. The signing key is `JWT_SECRET`, with a committed dev fallback in `application-dev.properties` — a fixed key on purpose, so tokens survive a container restart.
- **Dev accounts** (`dev` profile only, seeded by `DevUserSeeder` on every boot, idempotent): 25 accounts, 5 per role — `ec1`–`ec5`, `eo1`–`eo5`, `vs1`–`vs5`, `att1`–`att5`, `tech1`–`tech5`, **password `123456`** for all. Internal roles (ec/vs/technician) are organisation `ConnectSphere`; `eo1`/`eo2` + `att1`/`att2` are `Acme Pte Ltd`, `eo3`/`eo4` + `att3`/`att4` are `Globex Holdings`, `eo5` + `att5` are `Initech Asia` — deliberately arranged so same-org and cross-org pairs exist for AU04 data-scoping tests. Test by logging in first — HTTP Basic is gone, so `curl -u` no longer works:
  ```sh
  TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"ec1","password":"123456"}' | jq -r .accessToken)
  curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/venues
  ```
  These are dev fixtures committed to git on purpose; they must never exist in a real environment, which is what `@Profile("dev")` enforces.
- **Always use `docker compose up --build`**, not plain `up`. Plain `up` reuses whatever `csphere-backend` image already exists and does not rebuild on source changes — on 2026-09-21 a ten-day-old image seeded a fresh volume with an outdated schema and produced a Flyway checksum-mismatch boot failure that looked like a migration bug but was a stale image.
- Backend + DB (Docker Desktop must be running): `docker compose up --build -d`; tear down with `docker compose down` (add `-v` only to also wipe the Postgres volume, e.g. to reset migrations — confirm with the team first, as this is destructive).
- Backend tests: `./mvnw test` from `backend/` (needs a JDK — see README for running it inside a container against the `db` service if you don't have Java locally). **Verified 2026-09-12**: 13/13 tests pass (`EventRequestServiceTest`, `EventRequestControllerTest`, `ConnectSphereApplicationTests`) against a real Postgres 18 instance, including the V3 migration. `@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` in this Spring Boot 4.1.1 project (not the pre-4.x package) — needs `spring-boot-starter-webmvc-test` on the test classpath, added alongside the other per-feature test starters already in `pom.xml`.
- Frontend tests: `npm run test` (Vitest + React Testing Library, both added — none existed before). **Verified 2026-09-12**: 10/10 pass. `npm run build` (`tsc -b && vite build`) and `npm run lint` (ESLint) also verified clean.
- No lint/static-analysis tool is configured for the backend (no Checkstyle/Spotless in `pom.xml`).
- No seed-data command exists — `V1__mock_data.sql` is scaffolding only, not domain seed data.
- No CI command exists — there is no CI pipeline in this repository yet, though the backend and frontend are both now verified independently testable, which is what a CI job would need to run.
- **A mystery empty 401 is often a masked 404/500.** Spring Boot forwards unhandled errors to `/error`, which is a path like any other — under `.anyRequest().authenticated()` the forward is rejected and the real status is overwritten with a bodiless 401 carrying `WWW-Authenticate`. Re-send the request with `-u ec1:123456`: if the status changes, the 401 was never about authentication. Consider adding `/error` to `permitAll`.
- **Known environment gotcha, fixed 2026-09-12**: `backend/mvnw` had CRLF line endings from a Windows checkout, which breaks it inside any Linux container (`docker compose up --build` included) with `/bin/sh^M: bad interpreter`. Fixed in the working tree and a root `.gitattributes` added to prevent recurrence — **this fix is not committed yet**; commit `backend/mvnw` and `.gitattributes` together so teammates on Windows don't hit the same failure.

## Product Backlog Spreadsheet — safe reading/editing rules

The backlog is a **live Google Sheet**, edited collaboratively by the whole team in real time (see the link above) — treat it the way you'd treat a teammate's in-progress work, not a static file.

**How Claude Code can currently interact with it:**
- **Reading**: fetch the Google Sheet URL (or a specific tab, e.g. by its `gid`) — this returns a summarised, markdown-level view of visible content, not exact per-cell values or formulas. It's reliable for scope calls, priorities, epics, and story text, but treat exact IDs, point values, and formula behaviour as approximate until double-checked, especially for a sheet this dense (114 rows).
- **Precise/bulk extraction** (exact cell values, formulas, or the full acceptance-criteria text for many stories at once) is not reliably possible from a live Google Sheet fetch alone. If you need that level of precision, ask the team for a fresh exported `.xlsx` snapshot rather than assuming the fetched summary is complete.
- **Writing**: Claude Code has no direct write access to this Google Sheet in this environment. Do not attempt to edit it. If a story's scope, priority, points, sprint, or wording needs to change, **tell the team what change is needed and why** — a human applies it in Google Sheets.

**If the team ever hands you a downloaded `.xlsx` copy again** (e.g. for a deep, exact read), be aware it may contain real Excel threaded comments (`xl/threadedComments/*.xml`, `xl/persons/person.xml`) carrying genuine design rationale — confirmed present in the snapshot inspected on 2026-09-12. Read-only access (`openpyxl.load_workbook(..., read_only=True)`, or raw `zipfile`/XML parsing) is always safe. Do **not** do a normal load-then-save round trip with openpyxl or similar libraries — they don't understand threaded comments and silently replace the real text with a placeholder on save. If a change must be written to such a file, patch the raw XML directly (map sheet names to `sheetN.xml` via `xl/workbook.xml` + `xl/_rels/workbook.xml.rels`, edit only the target `<f>`/`<v>`/shared-string content, re-zip everything else unchanged) and diff the comment count/content before and after.

**Always ask before making any scope/content decision** (which stories are in/out of scope, wording changes, priority/points/sprint changes) — report what the team should change in the sheet, don't assume you can make the edit yourself.

Formula conventions established in this workbook (relevant if you're ever asked to design a formula for the team to paste in, since you can't edit the sheet directly):
- Never use `AGGREGATE` (Google Sheets has no such function) or `FILTER` (doesn't import reliably everywhere) — use the classic `INDEX + SMALL + IF + ROW` "nth match" pattern, which works identically in Excel/Google Sheets/LibreOffice.
- Any array-math formula must be entered as a true array formula (`Ctrl+Shift+Enter`, or `openpyxl.worksheet.formula.ArrayFormula`), not a plain formula.
- **Velocity** = sum of `Points Allocation` for stories whose `Sprint Backlog Status` = "Done" **only** — never sum "points allocated" for a sprint and call it Actual/Done velocity. The `Sprint Backlog Status` column on `User Stories` already resolves each story's live Done/In Progress/To Do/Product Backlog state; reuse it.
- After any formula change, recalculate with a real spreadsheet engine and read back **values**, not formula text, to confirm it actually works.
- Check for merged cells before writing into a cell that used to be a label — a merge silently swallows non-anchor-cell values.

Do not assume the backlog's current numbers (310 total points, 4×2-week sprints, 77.5 pts/sprint planned velocity) are final — several inconsistencies are still open (see `docs/decision-log.md`).

## Definition of Done

A story/feature is Done only when, per the Week 4 course material and this project's own DoD stories (DEV24/25/26 threaded-comment ACs):
- It traces to a Within-Scope backlog item and satisfies its stated acceptance criteria.
- Role access and server-side data-scoping have been considered and enforced (not just hidden in the UI).
- Relevant automated tests exist and pass (unit and/or integration, per the feature's risk).
- The code has been reviewed (by a teammate, or explainable line-by-line by whoever generated it with AI assistance).
- It works with existing repository conventions (layering, Flyway-owned schema, DTO/mapper pattern).
- Documentation/README/architecture diagrams are updated when the change affects them.
- The change is demoable end-to-end, or its limitation is explicitly documented.
- An item that does not meet this bar returns to the Product Backlog rather than being called "Done."

## Git Safety

- Work on a feature branch, not `main`. `feature/event_request` currently exists with no commits ahead of `main` — check with the team before assuming it's still the intended branch for the next slice.
- Never push, merge, rebase, reset, force-push, delete branches, delete data, alter production configuration, or commit unless the user explicitly asks.
- Never expose secrets. Do not commit `.env` files, tokens, API keys, passwords, or personal data.
- Prefer additive, reversible changes; explain any schema/data migration before applying it (remember: Flyway migrations are immutable once applied — add a new `V{n}` file, never edit an applied one).

## Engineering Workflow

Before changing code:
1. Read this file and the relevant `docs/` files, and re-check the live backlog spreadsheet for the current scope/priority/assignment of the story in question.
2. Inspect relevant source files, `pom.xml`/`package.json`, migration files, existing tests, README, and (once one exists) CI configuration.
3. State a brief plan: scope, files likely to change, assumptions, risks, and tests to run.
4. Ask targeted questions only where a decision blocks correct implementation.

When implementing:
- Build every story as one complete vertical slice ("strand of hair" — Jillian's term for it), not layer-by-layer across many stories: backend entity/DTO/mapper/repository/service/controller → that story's acceptance criteria encoded as automated tests → the corresponding frontend UI → its own tests → then an end-to-end check that the whole slice actually works together (e.g. a live smoke test against the running app, not just mocked tests). Don't build only the backend for several stories and defer the frontend, or vice versa.
- Make the smallest coherent change that completes an approved slice of user value (usually one backlog story).
- Follow existing repository conventions. Do not introduce a new framework, ORM, state-management approach, or dependency without explaining why and getting approval.
- Validate input at appropriate boundaries; return clear, user-friendly errors.
- Preserve/implement authorisation and data-scoping boundaries per the business rules above.
- Add or update tests with every behavioural change.
- Keep documentation and environment/example configuration accurate.

After implementing:
1. Run the closest relevant tests, lint/build/type-check commands available in the repository, and report actual results (not assumed success).
2. Report changed files, user-visible behaviour, tests run and their results, assumptions, and known remaining gaps.
3. Show the diff or a concise review summary before any commit.

## Do Not Assume

Do not assume:
- The technology stack, package manager, commands, database, deployment target, or CI workflow beyond what's verified above — this repository has no frontend router/state library, no frontend test framework, and no CI pipeline implemented yet.
- That the "Login as [Role]" selector is still the whole story — real credential-based authentication **is now being built** on the `feat/auth` branch (D19), ahead of the team's recorded D6a deferral. On `main` the interim selector still stands; on `feat/auth` it is being replaced. Check which branch you are on before assuming either.
- That every capability mentioned in the Week 1 customer briefing belongs in Release 1 — only the 20 named core features (and whatever the live backlog currently marks "Within Scope, Included" or "Added") do.
- That the backlog spreadsheet's current numbers (velocity, sprint dates, story-to-sprint assignment, `Assigned To` names) are stable — it is edited live; re-read it rather than relying on a cached figure, especially before sprint planning or reporting progress.
- That a venue is available solely because its capacity is large enough, or that a booking is confirmed without a verified status/workflow rule.
- That a field, entity, API, or business-status enum exists without inspecting `SCHEMA.md`/the migrations and the approved backlog.
- That "Developer", "Scrum Master", or "Product Owner" are application user roles — they are backlog-modelling categories for technical/process stories only (see "Users" above).
- That the Sprint Backlog Status shown in the spreadsheet (e.g. a story marked "Done" or "In Progress") is automatically reflected in the actual repository — cross-check against real commits/branches before relying on it (a known discrepancy exists as of 2026-09-12; see `docs/decision-log.md`).
