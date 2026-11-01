# ConnectSphere — Project Context for AI agents and the team

## Purpose

ConnectSphere is an Event Planning and Venue Booking System for ConnectSphere Event Services, a regional Southeast-Asian event-planning and venue-services organisation. It replaces fragmented coordination across email, spreadsheets, calendars, messaging apps, and manual documents with a single platform for planning and managing events, venues, equipment, and the people involved — Event Organisers, Event Coordinators, Venue Staff, Technical Support Staff, and Attendees. This is an SMU IS212 Software Project Management team deliverable: build a working, demonstrable, testable Release 1 that implements a customer-approved set of core features end-to-end, run under a real scrum process — not the broadest possible interpretation of the customer briefing.

> **Before writing or changing any test**, follow "Writing or changing a test — every time" under *Testing rules (IS212 Week 9)* below. CI fails the PR if a test file names no user story.

## Sources of Truth (read in this order)

1. `docs/product-context.md` — detailed requirements, roles, workflows, business rules, data model, NFRs, traceability.
2. `docs/decision-log.md` — settled architecture/process decisions vs. items still awaiting team confirmation.
3. `docs/implementation-roadmap.md` — the sequenced build plan and current slice.
4. **The live product backlog — Google Sheet** (canonical, edited live by the whole team):
   `https://docs.google.com/spreadsheets/d/1dXQFjJwVmFJHmmfvxb-sE67jzxiDz8YbbVPX-0YSYv0/edit?usp=sharing` (current link as of 2026-10-10, after the Week 7 customer changes; the older `10oMdrn…` and `19VlaC36…` links are superseded). Exact cell text can be pulled with `curl -sL "https://docs.google.com/spreadsheets/d/<id>/gviz/tq?tqx=out:csv&sheet=<Tab%20Name>"` — far more reliable than a summarised page fetch. (An unknown tab name silently returns the *first* tab, so check the header row.)
   Tabs: Visual Story Map, User Stories, Prioritised User Stories, Estimation, Task Board, Sprint Backlog 1–4, Test Cases – Sprint 1–4, Read Me. **Stories finished in earlier sprints are removed from the User Stories tab and live only on their Sprint Backlog tab** (e.g. EC01/EC02/EC03/EO26 are on Sprint Backlog 2, marked Done) — so search the sprint tabs before concluding a story does not exist. User Stories now holds the remaining work (~105 rows on 2026-10-10). This is the authoritative source for user stories, scope calls, priorities, points, sprint assignment, and per-person `Assigned To`. See "Product Backlog Spreadsheet" below for how to read/edit it safely.
   The repository's own copy, `Materials/Project/v2 ConnectSphereProductBacklog (4).xlsx`, was a snapshot of this sheet and is being retired by the team — if it still exists when you check, prefer the Google Sheet; if it's gone, that's expected, not a missing-file error.
5. `Materials/Project/` — the customer's actual requirements, newest first: **`Week 7 Customer Changes.pdf`** (six mandatory Release 1 changes, see below), `Week 7 Guide to Managing Changes.pdf` (how to reflect a change in backlog, ACs, points, design docs and tests), `Week 4 Project Instructions.docx` (the 20 core features and grading rubric) and `Week 1 Customer Briefing.docx` (original wide briefing). Where they differ, the newer document wins.
6. The current repository code, configuration, README, tests, schema/migrations, and CI files — always verify against these before assuming a capability exists.

If sources conflict, do not silently pick one — flag the conflict, name the affected feature, and ask.

Do not treat this file as proof that a specific framework, database, endpoint, UI, or feature already exists. Verify the repository first.

## Users

Application (product) roles — the original five are from the customer briefing; Safety Officer is an implementation scope extension approved for SO-C1–SO-C4:

- **Event Organiser** (external): client representative who creates/submits event requests and communicates with ConnectSphere.
- **Event Coordinator** (internal): assigned to coordinate an event; main internal point of contact.
- **Venue Staff** (internal): maintains venue records, decides venue availability/booking approval.
- **Technical Support Staff** (internal): maintains equipment records, decides equipment availability/reservation.
- **Attendee** (external): registers for and attends events where registration is enabled.
- **Event Coordinator Lead** (internal, added by the Week 7 customer changes; role `ecl`, dev account `ecl1`): oversees incoming requests. New requests enter an unassigned queue only the Lead sees; the Lead reviews basic information, assigns or reassigns an Event Coordinator, and sees all assignments and active events. Coordinators can no longer assign themselves.
- **Safety Officer** (internal, added by the Week 7 customer changes): runs the Operational Safety Check after venue and technical arrangements are confirmed and before the event proceeds to preparation; can approve, reject, or request changes (which send the event back to the relevant planning stage). Built: SO-C1–SO-C4 (event list/detail, persisted readiness checklist, decision), SO-C5/SO-C6 (a rejection or a request for changes needs a reason and names the checklist items it concerns, shown to the coordinator; the event still returns to the coordinator either way) and EC08 (coordinator sends for review, confirms after approval); see decision-log D22 and D30.

An **"Authenticated User"** cross-cutting capability (login, session, role-based access control) underlies all six roles — the backlog models this as its own set of stories because Week 4's "User Authorisation and Authentication" core feature applies identically to every role, not because it is another application role.

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

## Week 7 Customer Changes (VERIFIED, `Materials/Project/Week 7 Customer Changes.pdf`) — all mandatory for Release 1

The customer says all six must be in the Week 12 submission and expects no further Release 1 changes. Backlog stories created for them (2026-10-10 sheet) in brackets:

1. **Venue setup and turnaround time** — each venue has configurable setup and turnaround times, included in availability and conflict checks (a 10:00–12:00 event with 30 min setup and 45 min turnaround occupies 09:30–12:45). Bookings that become conflicting under the new rule are *identified*, never silently removed. (VS-C2, VS-C4)
2. **Venue becomes unavailable after booking** — Venue Staff can mark a venue temporarily unavailable even with future bookings. The event is **not** cancelled: affected bookings are identified, their coordinators informed, original event information preserved, and the coordinator can search for and request a replacement venue. (VS-C1, EC-C4, EC10, EO-C1)
3. **Multiple venues for one event** — an event can have several venue bookings, each checked independently for suitability, availability and conflicts; changing or cancelling one does not remove the others unless explicitly required. (EC-C2)
4. **Tentative venue holds expire** — Venue Staff may hold a venue while a coordinator finalises; every hold has an expiry date/time, after which the venue becomes available again; the coordinator is told before or at expiry; an expired hold is never a confirmed booking. (VS-C3, EC-C3)
5. **Event Coordinator Lead role** — see Users. (ECL-C1–C3, ECL-C7, ELC-C4–C6, EC-C1)
6. **Operational Safety Check (Safety Officer role)** — see Users. The event cannot proceed to preparation until the Safety Officer approves; assume they always respond. (SO-C1–C6)

**How to handle changes (Week 7 Guide to Managing Changes):**
- A *new* requirement becomes a **new story**; the original story stays Done (Scenario 2).
- An original AC that was **missed or is wrong** reopens the original story, with only the remaining work re-estimated (Scenarios 1 and 3).
- Every change is reflected in backlog, ACs (and a check that existing ACs still hold), story points, design docs (C4, class, sequence, data model, API specs) and tests (unit, functional, integration, E2E, regression).

**Implementation scope extension (user-authorised; backlog sync outstanding):** SO-C1–SO-C4, supplied for this implementation, add a Safety Officer event list/detail, persisted readiness checklist and checklist-gated Safety Officer approval (which, combined with EC08, marks the event Safety Approved for the coordinator to confirm; D22). This is an explicit exception to the general role-dashboard/checklist deferral below; it does not bring generic dashboards or reporting into scope. The live backlog remains canonical and could not be accessed during this implementation.

## Explicit Out-of-Scope / Deferred (VERIFIED, backlog `Out of Scope` rows)

Do not build these unless the backlog is updated to bring them in scope: event programme/multi-session recording, supporting-document attachments, in-app comments/discussion threads (EO18; the structured clarification timeline from D21 is in scope), Event-Coordinator "portfolio" browsing, rejection appeals, user guides, generic dashboards/role-based overview screens (except the SO-C1–SO-C4 console described above, and each role's console — side navigation plus a dashboard of that role's existing work — delivered as a task on that role's existing story, as TS09 did for Technical Support and EC09 for the Event Coordinator; backlog VS19/TS10/AT10 stay out of scope), most reporting/export features, attendance-vs-registration distinction, waiting lists, and technical-staff on-site assignment. Recurring/similar-event reuse and event categories are also out of the named 20 and not modelled.

## Functional & Non-Functional Requirements

Functional requirements are the 20 core features above, each with acceptance criteria on the `User Stories` sheet — treat AC text there as authoritative over any paraphrase here.

NFRs (VERIFIED, customer briefing §8, INFERRED into concrete stories on the backlog as DEV07/DEV08/DEV09/DEV10):
- **Performance**: common operations (view event, search venues, open calendar, check equipment, submit registration) should complete in a reasonable time — no numeric SLA given by the customer; do not invent one without confirming with the team.
- **Security**: role- and relationship-based access control; API-layer enforcement, not just UI hiding (DEV05).
- **Usability**: no extensive training required; responsive desktop + mobile (DEV09).
- **Reliability/consistency**: no contradictory information across event/venue/equipment/registration views.
- **Scalability**: designed for growth in events/users/clients/venues/equipment/registrations over ~3 years — no specific numeric target given.
- **Auditability**: what changed, who changed it, when (DEV07 — internal audit log). The initial schema captured `created_by` on `event_requests`; workflow activity, requirement review, hold and assessment histories now exist; see the current migrations and feature guides.
- **Maintainability**: architecture should allow future rules/features without a full rebuild — this is the stated reason for keeping schema ownership in Flyway migrations and documenting C4 diagrams (DEV01, DEV12).

## Critical Business Rules & Validation Rules (VERIFIED — customer briefing + backlog ACs)

- Capacity and required facilities are strict suitability blockers. A missing accessibility feature requires a written justification and Venue Staff judgement (D21); it must not be labelled automatically suitable.
- **Booking conflict detection**: a confirmed venue booking must affect whether that venue is considered available for other events over the same period. The current schema has **no DB-level overlap constraint** on `venue_bookings` — this must be enforced in application logic (see `SCHEMA.md` gap #1); do not rely on the frontend alone as the customer briefing and DEV06 both call for a backend concurrency-safe check.
- **Equipment availability** = `equipment_qty` − sum of overlapping `equipment_logs.quantity` for the requested window (see the query documented in `SCHEMA.md`). Damaged/maintenance equipment must not be treated as available.
- **Draft → Submitted** transition: a draft may be saved with incomplete information; submission must be blocked while required fields are missing, and the system must tell the user what's missing (EO01/EO02 ACs).
- **Event status** must be a single, consistent value derived from the approved status model — never computed differently by different screens (EC-NEW2 AC, threaded-comment note on B16 of Sprint Backlog 3: keep automatic status transitions in acceptance criteria, not in the user-story statement itself).
- **RBAC / data scoping**: an Event Organiser may access only their own organisation's requests; an Event Coordinator only events assigned/authorised to them; Venue Staff only venue records/bookings they manage; Technical Support Staff only equipment/reservations relevant to them; an Attendee only their own registration and confirmed event info they're entitled to see; a Safety Officer can read active event readiness and update/approve only through Safety Officer-authorised endpoints. Enforce this server-side (API), not only via hidden UI — direct URL access or role/ownership tampering must not grant wider access (AU04 refined AC, threaded comment E22 on Sprint Backlog 1, plus SO-C1–SO-C4).
- **Registration** is bounded by a defined capacity and an open/close period; registrations beyond capacity or outside the period must be prevented or handled per an agreed rule (waiting list is out of scope for Release 1 unless the backlog changes).
- **Equipment status update** (Available/Faulty/Unavailable) never itself creates, cancels, or alters a reservation — these are separate concerns (threaded comment E21 on Sprint Backlog 1).
- **Interim access model (team decision 10/9/2026 — now being superseded on `feat/auth`, see D19)**: real account creation and credential-based login (`AU01A/B/C`, `AU02`, `AU03`, `AU05`) are deliberately deferred. For now, the main page offers a "Login as [Role]" button per role, which routes straight into that role's views with no credentials involved. `AU04` (only see/do what your role permits) and `AU06` (clear message on an unauthorised action) still apply, but their mechanism is the selected role, not a verified account — whether that role must also be enforced server-side during this phase is an open question (`docs/decision-log.md` Q2). Do not build real registration/login screens unless the team brings them back into the current slice.

**Event request dates and attendance (EO-C2, D28).** A request can only be submitted, resubmitted or approved when its start is strictly in the future, its end is strictly after its start (compared as moments), and at least 1 person is expected. Drafts stay saveable. Organiser dates are entered and shown in Singapore time. The server's 422 carries `fieldErrors` (field → message), and the frontend mirrors the same wording in `eventRequestValidation.ts`. Decimals for whole-number JSON fields are refused globally (`accept-float-as-int=false`).

**Event change requests (EO03/EC-NEW3, D29).** A change to a submitted request or its event is a row in `event_change_requests` (one pending per request), never an edit. The proposal is checked by the same rules as submitting. The coordinator decides against `ChangeImpactService`'s live impact and must acknowledge every "attention" key. Approval applies all fields at once and returns a safety-stage event to Planning when arrangements are affected. Bookings keep their own windows (VS-C2): nothing is moved automatically, and moves are left as follow-ups. Equipment the same way (D37): before approval, a time change is checked against free stock at the new time (too few is an attention item); approval flags an *approved* equipment request `attention` when the new times leave a reservation's window or the equipment requirements text changed; reservations stay, readiness shows it outstanding, and the coordinator clears it by revising (D36) or marking it still suitable with a note.

## Data / Domain Model — VERIFIED from `backend/src/main/resources/db/migration/`

Authoritative reference: `backend/src/main/resources/db/migration/SCHEMA.md` (hand-written schema dictionary) and `V2__init_tables.sql`. Do not re-derive this from memory — read those files.

Entities include `users`, `venues`, `events`, `event_requests`, equipment/booking tables, `refresh_tokens`, `notifications`, and (V22, SO-C1–SO-C4) `event_readiness_items` and `event_safety_reviews`. The current schema dictionary and migrations are authoritative for the latest version and every column. `venue_bookings` has its own saved start/end and setup/turnaround snapshots (V25–V27), with event-time fallback only for nullable legacy rows. Its NOT NULL `event_id` requires the event to exist first. Read `SCHEMA.md` before writing any entity, repository, or migration.

**Known, team-acknowledged gaps in the current schema** (from `SCHEMA.md` §5 — PROPOSED to resolve, not yet decided): no DB-level venue-booking overlap protection; `request_type`/`equipment_type` single-char codes undocumented; `event_requests` allows null start/end while `events` requires them (the approval path must handle this); overlap between `equipment_requirements` (free text) and `equipment_requests.technical_requirement`; workflow-specific histories exist, while a universal audit log remains separate; no equipment return/check-in flag. Confirm resolutions with the team before assuming any of these are settled — see `docs/decision-log.md`.

`mock` / `mock_references` (`V1__mock_data.sql`) are scaffolding to demonstrate the MVC layering convention (see `backend/.../mock/*`) — not domain entities. Delete only once real entities exist and after team agreement.

## Existing Architecture & Technology (VERIFIED by repository inspection — 2026-09-12)

- **Backend**: Spring Boot **4.1.1**, Java **25**, Maven (`backend/pom.xml`, wrapper at `backend/mvnw`). Web MVC + RestClient + Actuator + springdoc-openapi (Swagger UI likely at the default path). Persistence: Spring Data JDBC **and** JPA both present; Flyway (`flyway-database-postgresql`) owns the schema — `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.generate-ddl=false` (`application.properties`). Lombok for boilerplate, MapStruct for entity↔DTO mapping. One demo vertical slice exists (`mock` package: controller → service → repository → entity → DTO → mapper) purely to show the intended MVC convention — no real domain controllers/services exist yet.
- **Security** (built on `feat/auth`, 2026-09-21/22 — see D19 in `docs/decision-log.md`, **not yet team-ratified**): `spring-boot-starter-security` (**Spring Security 7.1.1**, Framework 7.0.9 via the Boot 4.1.1 BOM) plus `spring-boot-starter-security-oauth2-resource-server` — note the `security-` prefix; the shorter `spring-boot-starter-oauth2-resource-server` still resolves but its own POM marks it deprecated, and every tutorial uses the old name. **This matters more than anything else when searching for help: virtually every JWT/Spring Security tutorial online targets Boot 3.x / Security 6.x, and Security 7 removed the deprecated non-lambda DSL — `.and()` chaining, `http.csrf().disable()`, and `WebSecurityConfigurerAdapter` will not compile.** Verified 7.1.1 API differences that break tutorials: `DaoAuthenticationProvider` has **only** a `DaoAuthenticationProvider(UserDetailsService)` constructor (no no-arg ctor, no `setUserDetailsService`) while the encoder still goes in via `setPasswordEncoder`; `NimbusJwtEncoder.withSecretKey(key).build()` and `NimbusJwtDecoder.withSecretKey(key).build()` both exist and default to HS256, so the `ImmutableSecret` JWKSource route — which defaults to RS256 and needs an explicit `JwsHeader` — is unnecessary. Boot 4 also moved classes: `EndpointRequest` is now at `org.springframework.boot.security.autoconfigure.actuate.web.servlet`, `@WebMvcTest` at `org.springframework.boot.webmvc.test.autoconfigure`.
  **What exists now (stages 0–7 of D19 + D20, all live-verified):** stateless filter chain (`config/SecurityConfig` — no `HttpSession`, CSRF disabled for the token API, `/actuator/**` and `/api/auth/**` permitAll, role rules per D20); `config/AuthenticationConfig` (a `ProviderManager` over a `DaoAuthenticationProvider`); `config/JwtConfig` (HS256 encoder/decoder + a `JwtAuthenticationConverter`); `user/` slice (`User`, `UserRole`, `UserRepository`, `UserPrincipal`, `AppUserDetailsService`, `DevUserSeeder`, `TokenService`, `RefreshTokenService`); `POST /api/auth/login|refresh|logout`. Access tokens are 15-minute HS256 JWTs claiming `sub` (user UUID), `iss`, `iat`, `exp`, `username`, `role`, `organisation`; refresh tokens are opaque 256-bit values, SHA-256-hashed in `refresh_tokens`, single-use, rotating, family-revoked on reuse. **HTTP Basic has been removed** — log in and send a Bearer token; `curl -u` no longer works. `@EnableMethodSecurity` is on. `common/web/RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) put filter-chain rejections into the `ApiError` shape, and an `AccessDeniedException` handler on `ApiExceptionHandler` does the same for `@PreAuthorize` denials. Role rules for every endpoint live in `authorizeHttpRequests` (D20); `/api/event-requests/**` is additionally scoped by the token's `organisation` claim, **not** by the old `X-Organisation` header, which is no longer read. **Still missing:** the frontend — `apiClient` sends no `Authorization` header, so the React app is 401 against its own backend until a login page replaces D6a's role selector.
- **Database**: PostgreSQL **18**, run via `docker-compose.yml` (`db` service, `csphere` database). Migrations live at `backend/src/main/resources/db/migration/V{n}__description.sql`; **never** hand-edit an already-applied migration — Flyway checksums it and the next boot fails. Add `V3__*.sql` instead, or reset dev DB with `docker compose down -v`.
- **Frontend**: React **19.2**, Vite **8**, TypeScript **~6.0**, ESLint **10** + typescript-eslint. Vitest + React Testing Library are installed (see Commands). The 2026-09-12 note that `App.tsx` was the untouched starter is stale — real screens now exist; inspect `frontend/src` rather than trusting this line.
- **CI** (`.github/workflows/ci-pipeline.yml`, extended for IS212 Week 9; see D23/D24) runs on PRs into `main`, on pushes to `main`, weekly on a schedule and on demand. Jobs: `traceability` (every test file names a story); `frontend-build` (`npm ci` → lint → tests with coverage → build); `backend-build` (unit, then `@Tag("integration")` tests on a Postgres service container, JaCoCo coverage); `audit` (`npm audit --audit-level=high`). Then `package` (jar + frontend bundle built **once**, SBOM + vulnerability report), then `smoke` (`.github/scripts/smoke.sh` against the packaged jar) and `e2e` (Playwright against the packaged jar + built frontend). Each run's summary page shows coverage and traceability. No deploy jobs: deployment is out of project scope. Per IS212 Week 6, CI is only a *gate* if `main`'s branch protection marks these jobs as required status checks. Whether `traceability`/`audit`/`smoke`/`e2e` have been added is not verifiable from the repo; ask before assuming a red check blocks merging.
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
  - **DEV16 exception (user-authorised 31 October 2026):** the shared venue catalogue in `backend/dev/seed/venues.json` is intentionally installed once by V61, following nullable coordinate support in V60. All supplied values are retained as shared demo data, including illustrative capacities. This exception applies only to this venue dataset; accounts and other environment-specific fixtures retain the general rule. See `docs/dev16-venue-seeding.md` for identity, collision, preservation and application rules.
  - **DEV16 coordinate follow-up (31 October 2026):** V62 maps the user's later coordinate references onto the existing 20 rooms, without altering V60/V61 or the original payload. `backend/dev/seed/venue_coordinates.json` retains identity, coordinates and precision notes, with source URLs omitted as requested. Existing coordinate values are preserved; see `docs/dev16-venue-coordinate-mapping.md`.
  - **DEV16 map first release (user-authorised 31 October 2026):** `/venue-staff` keeps its existing workflow cards with a shared React Leaflet venue map below them. Staff-only map endpoints provide aggregate counts and selected-venue booking summaries; future-role access needs its own server-side scoping. Date filtering indicates scheduled bookings, not availability. No seed or booking-decision rules change. See `docs/dev16-interactive-map.md`.
  - **DEV16 staff-facing presentation/notes follow-up (user-authorised 31 October 2026):** V64 rewrites only unchanged original additional-information notes into staff-facing planning guidance, preserving staff edits, illustrative/unverified-capacity caveats and every other venue/booking field. Original payload/V60–V62 are immutable; V63 is reserved on the AT09 notices branch. Staff map cards, headers, placeholders and copy use existing staff presentation styles. See `docs/dev16-venue-staff-notes.md`.
- **Granted authorities are a bare string match.** Since HTTP Basic was removed, `JwtConfig`'s `JwtAuthenticationConverter` is the only thing that grants authorities to a request; it produces `"ROLE_" + role.toUpperCase()`. (`UserPrincipal.getAuthorities()` still produces the same shape, but it is now only reached during login, where the authorities are not used for any authorisation decision — keep the two in step anyway, since a future non-token path would depend on it.) `hasRole("VS")` compiles to an exact `String.equals` against `"ROLE_VS"`, and a `GrantedAuthority` is just a wrapped string with no registry or validation — so `ROLE_vs` and `ROLE_VS` are unrelated, and a mismatch surfaces only as a 403 that reads like a broken rule. Worse, `@WithMockUser(roles = "VS")` tests would still pass while real Bearer requests 403, because that annotation builds the authority itself rather than going through the converter. Change the two together or not at all. The `role` claim itself stays lowercase — that is the domain value the frontend reads; the `ROLE_`/uppercase shape is Spring Security convention and translating into it belongs in the converter.
- **Request timeline (D21, V13).** Any action that changes an event request or its event's planning records one entry via `ActivityService.record(...)` **inside the same transaction** (it is `MANDATORY`, so it fails loudly without one); a failed action must leave no entry. A new kind of entry is one `ActivityType` constant with its audience, not a migration. `event_request_activity` is append-only by trigger. **Notifications are sent after commit** (`afterThisTransactionCommits`), never inline: a `REQUIRES_NEW` insert that fails at commit escapes its try/catch and fails the request. State-changing loads use `findForUpdate` (row lock) so double submits are serialised.
- **Notifications (EC-NEW4, EO-C1, D38, V54).** Create them only through `NotificationService`; it stamps who did it (`actor_name`/`actor_role`, from the JWT) and drops a notice to the person who did the action (AC15). Only `SELF_NOTICES` are exempt, so add a type there only if it is a receipt or an open follow-up. Never pass an actor yourself. A new kind is one `NotificationType` + a V-migration extending `chk_notification_shape` the V51/V54 way + a `linkPathFor` case + the bell's `describe()`. Organiser venue notices: publish `VenueArrangementChanged` from the venue module and let `OrganiserVenueNotices` decide; never notify organisers directly from venue code. Messages are written for their reader: no internal ids, times in Singapore time and labelled.
- **Event change log (EC-NEW5, D26).** Services outside `eventrequest` do not call `ActivityService` directly: they publish an `EventChange` and `ChangeLogRecorder` records it in the same transaction. A new kind of change is an `ActivityType` constant with its audience and a `major` flag. Normal/Major is computed on the server (`significance`), never in the UI. The log is read-only everywhere.
- **Statuses (D22, V23).** Never add a status label map on a screen: show the `journeyStatus` (`StatusView`) the server sends, via `JourneyStatusBadge`/`JourneyStepper`. A new status is one `JourneyStatus` constant (backend) plus its tone in `frontend/src/types/status.ts`. Event lifecycle moves go through `EventLifecycleService` only; Safety Officer decisions (SO-C4, `ReadinessService.review`) go through `recordSafetyDecision(...)`; never set `events.status` directly. Incoming migrations through **V26** are preserved. **V27** captures per-booking setup/turnaround durations; **V28** adds timing review and conflict notifications while preserving lifecycle notification support. Check the migration directory and other branches before assigning another version.
- **Check every branch before claiming a migration version.** `git ls-tree -r --name-only <branch> | grep V[0-9]` across all branches, not just your own — `origin/main` and five feature branches already carry a `V7`. The team has hit a version collision once (D15) and `feat/auth` deliberately claimed a colliding `V7` a second time, to be renumbered on merge.
- **Object-oriented modelling rules (IS212 Week 5, "Communicating Design & Collaborating on Code"):** use inheritance only for a real "is-a" relationship that *also* needs polymorphism (a subclass overriding a method so the same call site behaves differently per type at runtime) — not for data-only variation (e.g. a role label), which belongs on one flat class as a field/enum instead. When an association between two classes carries its own meaningful data (dates, quantities, a status), give it its own class rather than bolting fields onto either side. Prefer a plain association over aggregation, and aggregation over composition, unless the stronger relationship is genuinely true — composition only when the child has no independent existence. Model only classes an actual story/AC needs. Keep class/sequence diagrams as versioned PlantUML/Mermaid text committed to the repo, updated in the same PR as the code they describe, not a one-off exported image — see `docs/decision-log.md` D16 for how this was applied to the current domain model. **Class diagrams are generated, not hand-drawn (DEV15):** the tests in `backend/src/test/java/com/example/connect_sphere/uml/` write `docs/class-diagrams/{domain,full,auth}.generated.puml` on every backend test run (`DomainClassDiagramTest` needs the database). `EntityRelationshipDiagramTest` likewise writes the ER diagram of the migrated schema to `docs/er-diagrams/schema.generated.puml`. After changing a class or adding a migration, run the backend tests and commit the regenerated `.puml` files in the same PR — CI's "Generated diagrams up to date" step fails the PR otherwise. Never edit them by hand; change the filters or the clean-up rules in `ClassDiagrams.java` instead. `docs/class-diagram.stale.puml` is the old hand-drawn diagram, kept for reference only.

## Testing rules (IS212 Week 6 — Automated Testing #1 & CI)

The course grades whether tests are *evidence*, not whether they exist. Apply these to every test written for this project, human- or AI-generated:

- **Expected values come from the acceptance criteria, never from running the code.** A value copied out of the implementation can only confirm what the implementation already does; if code and test share a misunderstanding (e.g. "over $100" coded as `>=`), the suite stays green and wrong.
- **For every test, ask "what plausible wrong implementation would make this fail?"** If nothing would, the test is decoration — delete or strengthen it. Assert the exact outcome (`assertEquals(expected, actual)`, status *and* unchanged state), not vague truths like `balance >= 0` or `isNotNull`.
- **The five review questions** (use them when reviewing any PR's tests): which AC does it cover; what wrong implementation would fail it; can you explain setup/action/expected; is the expected value justified independently of the code; is it deterministic and non-vacuous.
- **Think in mutants.** Boundaries (`>` vs `>=`, start == end, attendance == capacity), dropped guards, missing state updates, and "failure must change nothing" ACs are where suites leak. Test both sides of every boundary and assert *nothing was written* on every rejection path — this project's ACs repeatedly say "does not change status / create a record if X fails", and that needs an explicit assertion.
- **Coverage is a diagnostic, not a target.** Low coverage proves a gap; 100% proves nothing about correctness.
- **FIRST**: Fast, Isolated (any order — fresh fixture per test), Repeatable (no flaky time/order dependence), Self-validating (assertions, no eyeballing), Timely (written with the code, TDD red→green→refactor where practical).
- **Structure**: one behaviour per test, Arrange-Act-Assert, named after the requirement (`rejectsBookingWhenAttendanceExceedsCapacity`), not the method (`testSubmit2`).
- **Pyramid**: most tests are unit tests (service logic in isolation); fewer integration tests (real DB/filter chain); fewest end-to-end (see the Week 9 rules below).
- **An agent (including Claude) reporting "all tests pass" is not evidence** — the required CI status check on the PR is. Never suggest merging, disabling, or bypassing a failing required check.

## Testing rules (IS212 Week 9 — Automated Testing #2 & Continuous Delivery)

Week 6 asked "is the evidence from one unit trustworthy?"; Week 9 asks "does it stay trustworthy across boundaries?" (`Materials/Week9/`, including the lab "From a Green Build to a Gated Release" and its reference pipeline). The full team-facing version, with examples, is **`docs/testing-strategy.md`**; decision-log D23 (CI) and D24 (testing and traceability) record why.

**The prof grades testing and its links to user stories and ACs.** Week 4 Project Instructions: *"test evidence should make it possible to trace important user stories or acceptance criteria to the relevant tests"*. Instructors may ask us to *"trace a requirement to its implementation and tests"*. The rubric's top band reads *"normal, boundary, conflict, and failure scenarios … 100% coverage unless not possible and stated why"*. So:

- **Every test traces to a story, mechanically.**
  - Backend classes carry `@Tag("<StoryID>")` for each story covered, next to `@Tag("unit")`/`@Tag("integration")`.
  - Frontend files name story IDs in `describe`/`it` titles or a `// @stories …` header.
  - CI's `traceability` job (`.github/scripts/traceability.py --check`) fails if a test file names no story. It regenerates `docs/test-traceability.md`, which also lists High-priority stories with no tests.
- **Test names say which AC and which kind of case**, e.g. `@DisplayName("EC08 AC26, AC27 (conflict): …")` or `it('EO04 AC14 (boundary): …')`. Case types: happy / negative / boundary / conflict / failure / cross-cutting (Week 4's 5 steps, plus the rubric's conflict and failure).
- **Unit vs integration traceability.** A unit test usually proves one story's AC. Integration and E2E tests often cut across several stories, so tag all of them.
- **Week 4 test cases go in the Sheet** (*Test Cases – Sprint N*: ID, scenario, pre-conditions, steps, data, expected, plus an execution record). **Remarks name the automated tests that execute each case**, so Sheet, ACs and code point at each other. Repository copies are in `docs/test-cases/` (e.g. `sprint3-jillian.md`, which maps all 95 ACs of EC-NEW2/EC08/EO04).

**Writing or changing a test — every time (all agents, no prompt needed).** CI's `traceability` job fails the PR if any test file names no story. Follow these steps for every test you create or edit, without being asked:

1. **Find the story ID** from the branch name (`git branch --show-current`).
   - Examples: `feat/vs-c3-tentative-venue-holds` → `VS-C3`; `feature/ecnew5-change_log` → `EC-NEW5`; `feat/ec08-…` → `EC08`.
   - Use the canonical form: uppercase, a hyphen before `C` or `NEW`, and two digits for plain numbers (`EO04`, `EC08`, `VS-C3`, `EC-NEW5`, `ECL-C7`, `TS01`, `SO-C4`, `AU04`, `DEV15`).
   - If the branch names no clear story, **stop and ask the user**. Never guess or invent an ID.
2. **Tag every test file you create or edit.**
   - **Backend** (`backend/src/test/**/*Test.java`): on the class, add `@Tag("<StoryID>")` next to exactly one layer tag. Use `@Tag("integration")` for tests that use a real database or Spring context (`@SpringBootTest`), and `@Tag("unit")` otherwise.
   - **Frontend** (`frontend/src/**/*.test.ts(x)`): make line 1 `// @stories <StoryID>`, and start the top-level `describe` title with the story ID.
   - **E2E** (`frontend/e2e/*.spec.ts`): make line 1 `// @stories <IDs>`, and start each test title with a story ID.
   - **Tests that cover several stories** (usually integration or E2E): tag **all** of them.
   - **Existing tags:** keep them and add yours. Never remove or rename another story's tag.
3. **Name each test after the AC it checks:** `"<StoryID> AC<n> (<case>): <what the user experiences>"`.
   - Backend example: `@DisplayName("VS-C3 AC7 (conflict): a hold that overlaps a confirmed booking is refused and nothing is saved")`.
   - Frontend example: `it('VS-C3 AC3 (negative): shows a validation error when the expiry is missing', …)`.
   - `<case>` is exactly one of: happy, negative, boundary, conflict, failure, cross-cutting.
   - **AC numbers come only from the acceptance criteria the user gives you**, numbered 1, 2, 3… in the order given. If you don't have them, ask. If the user can't give them, write just the story and case (`"VS-C3 (boundary): …"`). **Never make up AC numbers.**
   - Expected values come from the AC text, not from what the code currently returns.
4. **Cover more than the happy path.** For each AC, consider happy, negative (wrong role, missing or invalid input), boundary (limits, empty, exactly at the edge), conflict (overlaps, concurrent changes) and failure (the action fails and nothing is saved) cases. A test that checks something is refused also checks that nothing changed.
5. **Verify before saying you're done.**
   - Run `python .github/scripts/traceability.py --check` from the repo root. It must not list "Untraced test files".
   - Run the tests you touched, and report both results to the user.
   - Don't commit changes to `docs/test-traceability.md` unless asked.
6. **Never:**
   - weaken, skip or delete an existing test to make the check or build pass (CI's `test-protection.py` fails a PR that deletes a test file or removes tests or assertions);
   - change another story's tests or tags, except to add your own story's tag;
   - edit or disable `.github/scripts/traceability.py` or the CI workflow to get past the check.

   If something blocks you, stop and tell the user.

**Test doubles: pick by what the AC requires.** These corrections to the team's Week 9 notes are deliberate; use this vocabulary in Q&A.

| Double | Meaning | ConnectSphere example |
|---|---|---|
| Dummy | Must be passed, never used on this path | Repositories in `withdrawingNeedsAReason`: a blank reason is refused before any is touched |
| Stub | Canned answers from **any** collaborator (not only external APIs). Assert on **state**. | `availability.affected(booking)` → `true` shows readiness lists the unavailable venue. **Still a unit test**: integration means the real components run together (e.g. `EventLifecycleFlowTest` on real Postgres). |
| Mock | Checks the interaction happened, with the right arguments. Assert on **behaviour**. | Notifications: right recipient, `times(1)`, `never()` on failure; confirming never writes bookings |
| Fake | Working simplified implementation | Not used: we run **real PostgreSQL** (Week 9's "disposable real infrastructure") rather than a fake DB. Frontend `stubApi` is a stub plus spy, not a fake. |

- **Mock vs stub (Q&A):** mock when the requirement *is* the interaction; stub when you only control an input and check the result. **Never mock the unit under test.**
- **Doubles can agree with each other and still be wrong about reality** (dollars vs cents). Component tests stub `fetch` with hand-written JSON, so integration and E2E tests guard the real contract.
- **Expected values come from ACs, never from what the app shows.** This applies to codegen, generated and "healed" tests (Week 9 lab step 24: *"check its expected value against the brief"*). A healer that makes a test pass by changing what it checks has broken the test.
- **Test data:** fresh state per test (rolled-back `@Transactional`, or unique names plus cleanup); small helpers (`FlowSupport`, `readyEvent()`, `frontend/e2e/support.ts`); only the values that matter visible; never real personal data.
- **Flaky tests are fixed, never retried green.**
  - Causes: timing, order and shared state, time and date, randomness, external services, concurrency.
  - Fixes: wait for a condition (`expect(...)`, `findBy…`, health polls); inject the clock (`Clock`, `vi.useFakeTimers()`); fresh state; double the network boundary.
  - Anti-fixes: sleeps, `waitForTimeout`, retries (`playwright.config.ts` has `retries: 0`), weakened assertions, skips.
  - Real examples: the organiser wizard's **Next** saves first, so E2E waits for each step's field (`support.ts`); after another role's action, wait until the server shows it saved before the next role acts.
- **E2E (Playwright, `frontend/e2e/`)** covers only critical journeys, chosen from **High-priority stories**:
  - `golden-path.spec.ts`: EO01/02 → ECL assign → EC02 approve → EC03 venue → VS03 approve → EC08 send → SO-C4 approve → EC08 confirm → EO04/EO09;
  - `failure-paths.spec.ts`: rejection; safety changes requested;
  - `critical-journeys.spec.ts`: sign-in, wrong password, draft persists.

  Decisions happen in the browser; the API is used only for test data that isn't the journey (a venue) and to look up IDs. Locators by role/label/text. Run per `frontend/e2e/README.md`.
- **Smoke vs E2E:** smoke (`.github/scripts/smoke.sh`) is a few fast HTTP checks that the **packaged** jar is alive and roughly right, chosen from High-priority stories. E2E is browser journeys. Both run against the artifact built once in `package`, not the source.
- **Prove the gate** (lab steps 27–28): a change only a user would notice must fail E2E while unit tests stay green. Done: removing the print rule (EO04 AC26) left all 43 component tests green and failed only the E2E print step.
- **Coverage** is published on every CI run (`.github/scripts/coverage-summary.py`). It's a diagnostic, not a gate. Bring a story's classes to 100%, or list the exclusion with its reason in `docs/testing-strategy.md`. Uncovered branches are usually a missing boundary/failure test or dead code to delete.
- **Where tests run:** your machine (unit + fast integration) → CI on the PR (the required gate) → CI after packaging (smoke + E2E against the built artifact) → a weekly scheduled run (spots flaky tests).
- **Continuous delivery vs deployment** (examinable, *not* a project deliverable): delivery keeps every change releasable and a person approves the release; deployment releases every green build automatically. The lecture says CD "doesn't score extra marks", so the pipeline stops before deployment.

### Q&A readiness (testing)

Be ready to answer, for any test:
1. Which user story and which AC does it test? (Read its name or tags; `docs/test-traceability.md`; `docs/test-cases/`.)
2. What plausible wrong implementation would make it fail?
3. Why this double? Why a mock and not a stub?
4. Why is this a unit, integration or E2E test?
5. Where did the expected value come from? (The AC, not the output.)
6. Is it deterministic? (Clock, data, waits.)

## Working with AI agents (IS212 Week 10 — Agentic AI and the Software Team)

`Materials/Week10/` (lecture, Kahoot, the study-room-booking lab) and `Materials/Project/Week 10 CTO AI Check.docx`. Agents are delegates, not teammates: **a human stays accountable for every merged change.** Week 10's management questions are delegate, constrain, verify and own.

**Guidance vs enforcement.** Guidance (this file, `AGENTS.md`, `CLAUDE.md`) influences an agent but guarantees nothing. Enforcement (tests, CI, branch protection) blocks what it encodes. Put every rule that must always hold into enforcement, and keep the rest as guidance.

| Rule | Guidance | Enforced by |
|---|---|---|
| Every test traces to a story | Testing checklist above | CI `traceability` (`traceability.py --check`) |
| Never delete or weaken an existing test | Testing checklist rule 6 | CI `traceability` → `test-protection.py`: fails a PR that deletes a test file or reduces a file's tests or assertions. A reviewer approves a deliberate removal with the `tests-reviewed` label. |
| Code builds, lints and passes tests | Definition of Done | CI `frontend-build`, `backend-build`, `smoke`, `e2e` |
| Role access and data scoping | Endpoint checklist | Security matrix and scope tests (`CoordinatorAssignmentScopeTest` and others) |
| Expected values come from the AC, not from the code | Testing checklist rule 3 | **Not enforceable.** A human reviews tests against the AC (lecture PR #57: code and test shared one misreading, so CI stayed green). |
| Reuse existing helpers; no duplicate logic | Development conventions | **Not enforceable.** Human review. |

**Delegation dial.** Set autonomy by reversibility, blast radius and verifiability, not by how hard the task looks or how confident the agent sounds.

| Level | When | ConnectSphere examples |
|---|---|---|
| Guarded (agent works; CI must pass) | Easily undone, contained, well tested | Copy, styling, a frontend page whose ACs are fully tested, extra tests |
| Approval required (a named teammate reviews before merge) | Business rules or shared behaviour | Any story's backend logic, notifications, change log, a new endpoint |
| Human-led (agent assists) | Hard to undo or wide blast radius | Flyway migrations (never edit an applied one), `SecurityConfig` and role rules, data-scoping, deleting data, CI and branch-protection changes |

Nothing here is Unattended: every PR into `main` is reviewed.

**Review is the bottleneck.** Agents produce code faster than people can review it.
- Keep PRs small: one story per PR.
- Don't open more agent PRs than the team can review; limit work in progress.
- Count review effort when estimating a story.
- Return an oversized or unclear PR rather than skim it.

**Agent-era technical debt** (watch for all four in review):
- **Conventional:** duplicated helpers or inconsistent patterns.
- **Comprehension:** code nobody on the team can explain. It fails the Definition of Done.
- **Verification:** tests written to match the implementation instead of the AC.
- **Harness:** stale instructions here or in `docs/`. Update this file in the same PR as the change that makes it stale.

**Five questions for every AI-assisted PR** (in `.github/pull_request_template.md`):
1. What was delegated?
2. What constrained the agent?
3. What evidence supports the change?
4. Where is human review needed?
5. Who is accountable?

**CFO request (Week 10 CTO AI Check).** "What value are our teams adding beyond simply asking AI to build the system?" Answer it in the Release 1 slide deck (template due Week 11), with evidence from this repository. Examples of that evidence:
- stories checked against the customer documents, with wrong ACs raised (decision log D25, D26);
- architecture and process decisions (the decision log);
- the guidance plus enforcement above;
- tests traced to ACs (`docs/test-traceability.md`, `docs/test-cases/`);
- review findings that changed AI-written code.

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

**5a. Coordinator endpoints are scoped by assignment (ECL-C7, D25).** An Event
Coordinator may open or act on a request or event **only while it is assigned
to them**. The event's own `coordinator_id` is the source of truth once the event
exists, and reassignment moves both the request and the event in one transaction.
- **Check assignment first**, before any validation of the input, so a stranger
  learns nothing (403 `NotAssignedCoordinatorException`, the same plain
  message for everyone).
- **The Lead reads any request or event** (oversight) but never takes coordinator
  actions.
- **Never put another event's name or organiser in a coordinator response:**
  conflicts say "Booked by another event" and the period.
- **Every new coordinator endpoint gets a row** in
  `CoordinatorAssignmentScopeTest.coordinatorEndpoints()`.

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
- **Dev accounts** (`dev` profile only, seeded by `DevUserSeeder` on every boot, idempotent): 26 accounts: 5 per original role — `ec1`–`ec5`, `eo1`–`eo5`, `vs1`–`vs5`, `att1`–`att5`, `ts1`–`ts5` — plus one Event Coordinator Lead, `ecl1` (Week 7 customer change, ECL-C1), **password `123456`** for all. Internal roles (ec/vs/technician/ecl) are organisation `ConnectSphere`; `eo1`/`eo2` + `att1`/`att2` are `Acme Pte Ltd`, `eo3`/`eo4` + `att3`/`att4` are `Globex Holdings`, `eo5` + `att5` are `Initech Asia` — deliberately arranged so same-org and cross-org pairs exist for AU04 data-scoping tests. Test by logging in first — HTTP Basic is gone, so `curl -u` no longer works:
  ```sh
  TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"ec1","password":"123456"}' | jq -r .accessToken)
  curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/venues
  ```
  These are dev fixtures committed to git on purpose; they must never exist in a real environment, which is what `@Profile("dev")` enforces.
- **Dev equipment catalogue** (`dev` profile only, seeded by `DevEquipmentSeeder` on every boot, idempotent): demo projectors, microphones, speakers, mixers, laptops, webcams, cables, extension cords and tripods. Serial-tracked items get matching `AVAILABLE` demo units; entries use the agreed generic `E` equipment-type code. Seed rows are added only when absent and do not overwrite existing inventory.
- **Always use `docker compose up --build`**, not plain `up`. Plain `up` reuses whatever `csphere-backend` image already exists and does not rebuild on source changes — on 2026-09-21 a ten-day-old image seeded a fresh volume with an outdated schema and produced a Flyway checksum-mismatch boot failure that looked like a migration bug but was a stale image.
- Backend + DB (Docker Desktop must be running): `docker compose up --build -d`; tear down with `docker compose down` (add `-v` only to also wipe the Postgres volume, e.g. to reset migrations — confirm with the team first, as this is destructive).
- Backend tests: `./mvnw test` from `backend/` (needs a JDK — see README for running it inside a container against the `db` service if you don't have Java locally). **Verified 2026-09-12**: 13/13 tests pass (`EventRequestServiceTest`, `EventRequestControllerTest`, `ConnectSphereApplicationTests`) against a real Postgres 18 instance, including the V3 migration. `@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` in this Spring Boot 4.1.1 project (not the pre-4.x package) — needs `spring-boot-starter-webmvc-test` on the test classpath, added alongside the other per-feature test starters already in `pom.xml`.
- Frontend tests: `npm run test` (Vitest + React Testing Library, both added — none existed before). **Verified 2026-09-12**: 10/10 pass. `npm run build` (`tsc -b && vite build`) and `npm run lint` (ESLint) also verified clean.
- No lint/static-analysis tool is configured for the backend (no Checkstyle/Spotless in `pom.xml`).
- Optional manual demo scripts live in `backend/dev/seed/`, separate from Flyway and startup seeding. VS-C3 uses `./backend/dev/seed/vsc3-seed.ps1 -LocalDev -ExpiryMinutes 3` against a local backend: it creates a fresh labelled batch through the real APIs, retains prior attempts, and writes IDs/links under ignored `logs/vsc3/`. See `docs/vsc3-manual-testing.md` for scenarios and safeguards. `V1__mock_data.sql` remains scaffolding only.
- Before opening a PR run what CI runs: `npm ci && npm run lint && npm test && npm run build` (frontend) and `./mvnw -B -ntp clean verify` (backend), plus `npm run test:e2e` if you changed a journey it covers. A local "all tests pass" is Week 6's layer 1 feedback; the CI check on the PR is the authoritative one.
- **A mystery empty 401 is often a masked 404/500.** Spring Boot forwards unhandled errors to `/error`, which is a path like any other — under `.anyRequest().authenticated()` the forward is rejected and the real status is overwritten with a bodiless 401 carrying `WWW-Authenticate`. Re-send the request with `-u ec1:123456`: if the status changes, the 401 was never about authentication. Consider adding `/error` to `permitAll`.
- **Known environment gotcha, fixed 2026-09-12**: `backend/mvnw` had CRLF line endings from a Windows checkout, which breaks it inside any Linux container (`docker compose up --build` included) with `/bin/sh^M: bad interpreter`. Fixed in the working tree and a root `.gitattributes` added to prevent recurrence — **this fix is not committed yet**; commit `backend/mvnw` and `.gitattributes` together so teammates on Windows don't hit the same failure.

## Product Backlog Spreadsheet — safe reading/editing rules

The backlog is a **live Google Sheet**, edited collaboratively by the whole team in real time (see the link above) — treat it the way you'd treat a teammate's in-progress work, not a static file.

**How Claude Code can currently interact with it:**
- **Reading**: use the `gviz/tq?tqx=out:csv&sheet=<Tab>` export from the Sources of Truth section. It returns exact cell values (including full AC text) for one tab, as long as the sheet stays shared "anyone with the link". It does not return formulas or comments.
- For formulas or threaded comments, ask the team for an exported `.xlsx` snapshot.
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
- Relevant automated tests exist and pass (unit and/or integration, per the feature's risk), covering its normal, boundary, conflict and failure cases.
- Every test traces to the story (story tag or ID) and names the AC and case type it checks; `python .github/scripts/traceability.py --check` passes.
- The story's Week 4 test cases are recorded on its *Test Cases – Sprint N* lane in the Sheet, with an execution record and the automated tests named in Remarks.
- The classes behind the story are fully covered, or each exclusion is stated with its reason in `docs/testing-strategy.md`.
- A change to a critical journey keeps the E2E suite (`npm run test:e2e`) green.
- The code has been reviewed (by a teammate, or explainable line-by-line by whoever generated it with AI assistance). An AI-assisted PR answers the five Week 10 questions in the PR template, and a named teammate is accountable for it.
- No existing test was deleted or weakened, or a reviewer approved the removal with the `tests-reviewed` label.
- It works with existing repository conventions (layering, Flyway-owned schema, DTO/mapper pattern).
- Documentation/README/architecture diagrams are updated when the change affects them.
- The change is demoable end-to-end, or its limitation is explicitly documented.
- An item that does not meet this bar returns to the Product Backlog rather than being called "Done."

## Course timeline (from IS212 materials, for context)

Week 6: automated testing + CI (above). Week 7: six mandatory customer changes (above) and the change-management guide; 20-min ungraded process consult (backlog, Sprint 1 review, tooling, on track for Release 1?) — not a customer clarification session; individual Automated Testing assignment and Topic Research reservation due Friday Week 7. Week 9: quiz on Weeks 5–6, plus integration/E2E testing and CD/release pipelines. Week 10: agentic AI and the software team (above); group Topic Research presentation (7 min + demo); the CFO asks what value teams add beyond AI-generated code (answer in the Release 1 slide deck). Week 11: final quiz on Weeks 9–10 (start of class, in person, LockDown Browser; best 3 of 4 quizzes count); slide-deck template released. Week 12: Release 1.

## Venue booking timing (implemented 14 October 2026)

An event can have several venue bookings, each with its own saved start/end window and setup/turnaround snapshot. Occupied time expands the booking window, not the advertised event window. Event times are a fallback only for legacy nullable booking fields. Keep incoming batch submission, safety-review lifecycle actions and notifications. The existing one-active-booking-per-venue-per-event rule remains. Venue defaults apply to future requests; changing them does not rewrite saved booking durations. See [timing workflow and migration policy](docs/venue-booking-duration-snapshots.md) and [booking API](docs/venue-booking-api.md).

## AT04 — attendee registration history

Attendee pages share exactly two status badges, with lifecycle first and personal
registration/availability second. The read-only event summaries/details expose
owned status and availability fields; enums and AT01 mutation rules stay intact.
See [badge priorities and verification](docs/attendee-status-badges.md).

AT03 builds on this history: complete saved confirmations reuse AT02's approved
venues, and V55 links notifications/outbox payloads to the exact registration.
Registration and durable confirmation enqueueing share one transaction; the
existing outbox delivers after commit and retries with a stable identity. See
[AT03 contract](docs/registration-confirmation.md). Do not restore the earlier
registration after-commit enqueue callback: it leaves a durability gap.

AT04 registration history is implemented under `/api/attendee/registrations` and
`/attendee/my-registrations`. Reads scope to the token subject and preserve stored
registration history separately from event lifecycle. AT08 now adds the owned
withdrawal command and confirmation dialog. See [AT04 API and scope](docs/attendee-registration-status-api.md).
Awaiting re-confirmation describes a previously confirmed event returned to
planning/safety checks, not the initial processing of a new EO request. See the
[manual workflow and current local demo caveats](docs/attendee-registration-status-manual-testing.md).

## AT08 — attendee withdrawal

V56 adds nullable
`event_registrations.withdrawn_at`, AT04 list/detail exposes `withdrawnAt`, and
`POST /api/attendee/registrations/{id}/withdraw` uses token ownership and shared
eligibility. The command locks the event then the owned registration, records
the date and retains the original reference/history. Historical dates stay
unknown. The details page has a confirmation dialog, immediate saved status,
SGT withdrawal date and reconciliation after uncertain responses. V57 adds
the personal withdrawal receipt; its stable ID and outbox enqueue join the
withdrawal transaction, with delivery/retry after commit. This self-receipt is
exempt from actor suppression. AT07/AT09 remain separate stories. See
[AT08 contract](docs/attendee-withdrawal-api.md).

The [29 October AT08 review](docs/at08-implementation-review.md) reproduced a P2
reconciliation race, subsequently fixed in the [attendee workflow audit](docs/attendee-workflow-audit.md).
Use `useAttendeeResource` for attendee reads and reconciliation; mutations
invalidate older results, while pending polls do not starve valid slow reads.
Event routes scope pending actions to their event, successful reads clear old
errors, and failed registration responses are reconciled before showing failure.
Notification read mutations block stale unread polls. `/attendee/register`
redirects to Browse events. Backend locking/deduplication and V56/V57 are unchanged.

## Git Safety

- Work on a feature branch, not `main`. `feature/event_request` currently exists with no commits ahead of `main` — check with the team before assuming it's still the intended branch for the next slice.
- Never push, merge, rebase, reset, force-push, delete branches, delete data, alter production configuration, or commit unless the user explicitly asks.
- Never expose secrets. Do not commit `.env` files, tokens, API keys, passwords, or personal data.
- Prefer additive, reversible changes; explain any schema/data migration before applying it (remember: Flyway migrations are immutable once applied — add a new `V{n}` file, never edit an applied one).

## Engineering Workflow

Before changing code:
1. Read this file and the relevant `docs/` files, and re-check the live backlog spreadsheet for the current scope/priority/assignment of the story in question.
   **Always verify against `Materials/Project/`** (customer briefing + Week 4 Project Instructions, and anything added there later). Every story, AC, design choice or recommendation should trace to a named core feature, briefing section or rubric row. Flag anything those documents don't support, and anything they require that no story covers. The backlog and this file are the team's interpretation; `Materials/Project/` is the customer's actual requirement. Where Week 4 and Week 1 differ, **Week 4 wins** as the more recent source; use Week 1 only for detail Week 4 doesn't cover.
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
- The technology stack, package manager, commands, database, deployment target, or CI workflow beyond what's verified above — re-inspect `frontend/package.json` and `.github/workflows/` rather than trusting any snapshot here.
- That the "Login as [Role]" selector is still the whole story — real credential-based authentication **is now being built** on the `feat/auth` branch (D19), ahead of the team's recorded D6a deferral. On `main` the interim selector still stands; on `feat/auth` it is being replaced. Check which branch you are on before assuming either.
- That every capability mentioned in the Week 1 customer briefing belongs in Release 1 — only the 20 named core features (and whatever the live backlog currently marks "Within Scope, Included" or "Added") do.
- That the backlog spreadsheet's current numbers (velocity, sprint dates, story-to-sprint assignment, `Assigned To` names) are stable — it is edited live; re-read it rather than relying on a cached figure, especially before sprint planning or reporting progress.
- That a venue is available solely because its capacity is large enough, or that a booking is confirmed without a verified status/workflow rule.
- That a field, entity, API, or business-status enum exists without inspecting `SCHEMA.md`/the migrations and the approved backlog.
- That "Developer", "Scrum Master", or "Product Owner" are application user roles — they are backlog-modelling categories for technical/process stories only (see "Users" above).
- That the Sprint Backlog Status shown in the spreadsheet (e.g. a story marked "Done" or "In Progress") is automatically reflected in the actual repository — cross-check against real commits/branches before relying on it (a known discrepancy exists as of 2026-09-12; see `docs/decision-log.md`).

## VS17 — approved venue requirement notifications (24 October 2026)

VS17 is user-confirmed; VS15 remains the venue calendar. Approved venue-related
event changes now snapshot each active booking's requirements and create staff
notifications atomically with approval (V34). Existing EO/EC activity history,
semantic comparisons and impact checks are reused. V35 now adds immutable review outcomes, staff booking
page history/indicator, current suitability checks, and assignment-scoped coordinator
outcome visibility and notifications. Review actions do not change reservations. See `docs/venue-requirement-changes.md` for
scope, access limitations, transaction behaviour and outstanding source verification.


## VS17 — live suitability (25 October 2026)
Relevant venue edits or approved requirement changes now invalidate unsuitable approved bookings
into `attention` while preserving their reservation. Staff explicitly restore approval through
booking reassessment; EC/EO pages refresh automatically and events return through the existing
Planning/safety flow. V36–V38 add the state and immutable assessment snapshots. Existing decided
requirement reviews remain historic. See docs/venue-requirement-changes.md.

## Venue Staff presentation audit (25 October 2026)
Staff home now links to implemented features without scaffold copy. Shared booking
labels and attention colours apply to staff lists, details, previews and calendars;
attention reservations remain visible. Reassessment reuses existing review styles,
review loading failures are retryable, and operational-issue inputs explicitly use
Singapore time. See `docs/venue-staff-presentation-audit.md` for scope, evidence and
completed structured-hour fixture updates and full regression evidence. No
business-state or reservation rules were changed by the presentation audit.


## Venue workflow repairs (25 October 2026)

The user authorised all audit corrections and unavailable-period release, then explicitly retained shared Venue Staff access. Private staff booking GETs now require VS. Timed issues share the venue lock and block safety readiness. Live impact derives from current occupied intervals, active unavailability and timed issues. Final events do not block availability. The original different-venue-only replacement policy is superseded by the 26 October decision below. V48–V49 add audited period release and durable lifecycle/notification work. See [workflow repairs](docs/venue-workflow-repair.md) for contracts, retries, migration safety and verification. The live backlog still needs human synchronisation.


## Multiple venue arrangements (26 October 2026)
User-authorised EC-C2/EC08/EC10/SO-C2 implementation: per-booking requirements, durable unresolved requirement groups, same-venue and healthy-booking replacement retaining original reservations until approval, and per-booking safety evidence. Rejected requirements need replacement or explicit withdrawal. This supersedes earlier different-venue-only restrictions. See `docs/multiple-venue-bookings.md`; live backlog synchronisation remains outstanding.

## Venue Staff coverage gates (31 October 2026)

At the user's explicit request, Venue Staff/map frontend coverage is now an exception
to diagnostic-only coverage reporting: `frontend/vite.config.ts` requires 100%
statements, branches, functions and lines under `features/venueStaff`,
`features/venueMap` and `components/venue`. Backend `verify` requires 100%
instructions, branches, lines and methods for the `venuemap` package. Other shared
backend/role modules remain diagnostic; do not claim repository-wide 100% coverage.
Keep the existing test-quality rules and story tags. No coverage-ignore comments
were added. See `docs/venue-staff-coverage-audit.md` for scope, fixes and evidence.

## EC venue maps (1 November 2026)

User-authorised DEV16 extension: EC venue search and per-event venue requests reuse
the shared lazy map. Request layout keeps needs on the left and map/search/choices
on the right. The map and list share filtered options; multi-room selections and
grouped marker/popup highlights stay in sync. Opening a marker only browses;
individual popup toggles use the existing request selection logic. EC reads only
its existing search/assigned-event options, never the VS map-summary endpoints.
No backend permissions or booking rules change. Shared map/filter coverage and
the EC map adapter are gated at 100%; see `docs/dev16-coordinator-map.md`.
