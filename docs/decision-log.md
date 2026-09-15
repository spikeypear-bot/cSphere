# ConnectSphere — Decision Log

Format: one entry per decision. Status is either **Decided** (VERIFIED — actually implemented/committed, or explicitly agreed by the team and recorded here with a source) or listed under "Awaiting Team Confirmation" (PROPOSED/OPEN — inferred from artefacts or raised by inspection, not yet a team decision).

## Decided

### D1 — Schema is owned by Flyway migrations, never by JPA auto-DDL
- **Status:** Decided (VERIFIED — implemented).
- **Context:** Need one authoritative source for the database schema.
- **Decision:** `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.generate-ddl=false` (`backend/src/main/resources/application.properties`); all schema changes go through `backend/src/main/resources/db/migration/V{n}__description.sql`.
- **Consequences:** Migrations are immutable once applied — a mistake requires a new `V{n+1}` file, not an edit; JPA entities can be added incrementally without needing to exist for every table.
- **Source:** `application.properties`; `SCHEMA.md` §4/§6.

### D2 — Database is PostgreSQL 18, run via Docker Compose
- **Status:** Decided (VERIFIED — implemented).
- **Decision:** `docker-compose.yml` runs a `postgres:latest` (resolved to PG18 in practice) `db` service plus the Spring Boot `backend` service; no containerised frontend service.
- **Source:** `docker-compose.yml`.

### D3 — Backend stack: Spring Boot 4.1.1 / Java 25, layered Controller→Service→Repository→DTO/Mapper
- **Status:** Decided (VERIFIED — implemented as a demo slice).
- **Decision:** Confirmed via `backend/pom.xml` (parent version, dependencies) and the `mock` package's layering, which is explicitly commented as demonstrating the intended convention for all future entities.
- **Source:** `backend/pom.xml`; `backend/src/main/java/com/example/connect_sphere/mock/*`.

### D4 — Frontend stack: React 19 / Vite 8 / TypeScript, no router/state-lib/test-framework chosen yet
- **Status:** Decided only as to what currently exists (VERIFIED); the *gaps* (router, state management, test framework) are Awaiting Team Confirmation below.
- **Source:** `frontend/package.json`; `frontend/src/App.tsx` (unmodified Vite template as of 2026-09-12).

### D5 — Product backlog lives in a live, collaboratively-edited Google Sheet
- **Status:** Decided.
- **Context:** The team originally worked from a downloaded `.xlsx` snapshot (`Materials/Project/v2 ConnectSphereProductBacklog (4).xlsx`), which contained real Excel threaded comments with design rationale.
- **Decision:** As of 2026-09-12, the canonical backlog is the Google Sheet at `https://docs.google.com/spreadsheets/d/19VlaC36ed5ABgLAl9t8qhi5Eh4zO7V2JjtxRyCrNPjI/edit?usp=sharing`; the local `.xlsx` snapshot is being retired by the team.
- **Consequences:** Claude Code can read the sheet (via a web fetch, which returns a summarised view — not exact per-cell values) but has no direct write access to it; any backlog change must be reported to the team to apply, not made directly.
- **Source:** User instruction, 2026-09-12; cross-checked live via fetch (same 12 tabs, same role/story counts as the retired snapshot).

### D6a — Interim access model: role-selector instead of real authentication, for now
- **Status:** Decided.
- **Context:** Team chat (Haoming, Calynn — 10/9/2026, ~23:00–23:13): the team wants to avoid building account creation/credential-based login yet, so that everyone can start on role-specific pages immediately.
- **Decision:** The main page presents a button per role ("Login as Venue Staff" / "Login as Event Coordinator" / "Login as Event Organiser" — and, by the same logic, Technical Support Staff and Attendee, since the backlog scopes all five roles) — clicking one takes the user straight into that role's views, with no account, credentials, or verification involved. Real account creation and credential-based authentication (`AU01A/B/C`, `AU02`, `AU03`, `AU05`) are **deliberately deferred**, to be added in a later slice once role-specific pages exist to log in to.
- **Consequences:** `AU04` (view/perform only actions permitted for the selected role) and `AU06` (clear message on an unauthorised action) are still in scope for the current phase, but their mechanism changes — "role" is now whatever the user selected on the login-as screen, not a value read from a verified credential. **Open question this raises** — see Q2 below, now narrowed rather than closed: does the selected role need to be enforced server-side during this phase (e.g. passed as a header/claim the backend checks), or is enforcement purely front-end routing until real auth lands? Resolve before implementing `AU04`.
- **Source:** Team chat, 10/9/2026 (relayed by Jillian).

### D6 — Sprint cadence: four ~2-week sprints, 2026-09-07 → 2026-11-01
- **Status:** Decided (VERIFIED, from the backlog's `Estimation`/`Sprint Backlog N` sheets).
- **Source:** Backlog `Estimation` sheet (`NumSprints`=4) and each `Sprint Backlog N` sheet's Start/End Date cells.

### D7 — EO01/EO02/EO15 implemented; schema gaps from Q3 resolved as part of it
- **Status:** Decided (VERIFIED — implemented and tested, 2026-09-12).
- **Context:** Jillian's Sprint 1 assignment (per the backlog's `Sprint Backlog 1` tab: `EO01`, `EO02`, `EO15`) was the first vertical slice built. Implementing it required resolving Q3 (no literal "draft" status) immediately, since EO01 cannot exist without it.
- **Decision:** `V3__event_request_draft_support.sql` adds `draft` to `event_request_status` and relaxes `event_name`/`purpose`/`expected_attendance`/`venue_requirements` to nullable (matching the existing nullable start/end pattern); `EventRequestService` enforces completeness only at submission. Full backend (entity/DTO/mapper/repository/service/controller/exceptions) and frontend (role-selector landing page, "My event requests" list, a 5-step draft/submit wizard) implemented for these three stories, scoped by the interim `organisation` value from D6a rather than a real account.
- **Consequences:** `SCHEMA.md` and the `event_requests` table now differ from the original V2 migration — see the file itself for the column-by-column update. Q3 is resolved. `request_type` TODO also resolved as part of this (see D7a below).
- **Source:** This session's implementation work, verified by 13/13 backend tests and 10/10 frontend tests passing (see D9).

### D7a — `request_type` code resolved: `C` = creation, `A` = amendment
- **Status:** Decided (VERIFIED — implemented in V3, used by `EventRequestService`).
- **Context:** `SCHEMA.md`'s own TODO flagged that "creation" and "change" both start with C.
- **Decision:** `C` = creation request (`event_id IS NULL`), `A` = amendment/change request (`event_id IS NOT NULL`).
- **Source:** `V3__event_request_draft_support.sql`; `SCHEMA.md` §2.

### D8 — Fixed: `backend/mvnw` had CRLF line endings, breaking every Linux-container build
- **Status:** Decided (VERIFIED — fixed in the working tree 2026-09-12; **not yet committed**).
- **Context:** `backend/mvnw` was checked out with Windows line endings (no `.gitattributes` existed to prevent it). Any Linux-based execution — including `docker compose up --build`, the exact command README/CLAUDE.md tell people to run — failed with `/bin/sh^M: bad interpreter: No such file or directory`. This was a pre-existing, undiscovered break in the documented setup path, not something introduced this session.
- **Decision:** Converted `backend/mvnw` to LF in the working tree; added a root `.gitattributes` (`* text=auto eol=lf`, with `.cmd`/`.bat` kept as `eol=crlf`) to prevent recurrence on future checkouts.
- **Consequences:** **Someone needs to `git add backend/mvnw .gitattributes` and commit** — the fix only affects this working tree until then. Anyone who re-clones before that commit will hit the same failure.
- **Source:** Discovered and fixed this session while verifying the backend actually builds.

### D9 — Test stacks verified working (backend and frontend)
- **Status:** Decided (VERIFIED — actually run, 2026-09-12, not assumed).
- **Context:** Neither stack had any tests beyond a single Spring Boot smoke test before this session.
- **Decision/finding:** Backend: `spring-boot-starter-webmvc-test` added to `pom.xml` (the other per-feature test starters — data-jpa, data-jdbc, restclient, flyway — already existed; web MVC's was missing). `@WebMvcTest` resolves from `org.springframework.boot.webmvc.test.autoconfigure` in this Spring Boot 4.1.1 project — a different package than pre-4.x Spring Boot, found by inspecting the actual downloaded jar rather than guessing. `@MockBean` is superseded by `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`) in this version. Frontend: Vitest + React Testing Library + jsdom added (none existed); `npm run test` / `npm run test:watch` scripts added.
- **Consequences:** Both `./mvnw test` (backend) and `npm run test` (frontend) are now real, working commands — see `CLAUDE.md`'s Commands section for the verified counts.
- **Source:** This session's verification runs (13/13 backend tests, 10/10 frontend tests, both against real dependencies — Postgres 18 for the backend, jsdom for the frontend — not mocked away).

### D10 — `event_requests.accessibility_needs` is `text[]`, not the shared `accessibilities` enum-array type
- **Status:** Decided (VERIFIED — implemented, and the only option of three actually tried that worked against real Postgres).
- **Context:** Persisting a `List<AccessibilityFeature>` against the native Postgres `accessibilities[]` column repeatedly failed in Hibernate 7.4.5 (bundled with Spring Boot 4.1.1), discovered only by running real inserts against a live Postgres 18 instance — this was invisible in mocked unit tests, which is exactly why the vertical-slice workflow (build backend → frontend → then verify live end-to-end, not just mocked tests) caught it. Two working approaches were tried and both failed:
  1. `@JdbcTypeCode(SqlTypes.ARRAY)` (with or without `@Enumerated(STRING)`) — Postgres rejected the resulting `smallint[]`/`character varying[]` bind parameter: *"column 'accessibility_needs' is of type accessibilities[] but expression is of type ..."*.
  2. A custom Hibernate `UserType` binding via `Connection.createArrayOf("accessibilities", ...)` — this is the textbook JDBC-level fix for named Postgres array types, but it broke `SessionFactory` bootstrap entirely: `ClassCastException: CustomType cannot be cast to BasicPluralType`, because Hibernate 7's DDL/cast-type-name resolution for array-typed attributes specifically requires its own `BasicPluralType`, not an arbitrary `UserType`.
- **Decision:** `V4__event_request_accessibility_needs_as_text.sql` changes just this one column to `text[]`. `@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.ARRAY)` on a `text[]` column works cleanly (no named-type cast problem) and Java-level enum validity is unaffected.
- **Consequences:** This column no longer has Postgres-level enforcement that its values are one of the 7 valid accessibility labels — only Java-level (`AccessibilityFeature` enum) enforcement remains for it. `venues.venue_accessibilities` and `events.accessibility_needs` are untouched and still use the real enum-array type with its GIN index; **do not copy this table's mapping** if/when those entities are implemented — either solve the enum-array binding properly for them (worth revisiting once a fix is known — this may be a Hibernate 7.4.5-specific gap that gets patched), or make the same `text[]` trade-off deliberately and record it here.
- **Source:** This session's implementation of EO01/EO02, verified against a real Postgres 18 container (not mocked).

### D11 — Project context file renamed `CLAUDE.md` → `AI_Context.md`
- **Status:** Decided (VERIFIED — implemented 2026-09-12, on Jillian's instruction).
- **Context:** Not everyone on the team uses Claude as their AI coding assistant; a file literally named `CLAUDE.md` reads as tool-specific and other teammates' AI tools have no reason to open it.
- **Decision:** The full project-context document (Purpose, Sources of Truth, Users, Scope, Business Rules, Data Model, Architecture, Conventions, Commands, DoD, Git Safety, Engineering Workflow, Do Not Assume) moved to `AI_Context.md` at the repository root, verbatim. A 4-line `CLAUDE.md` stub was kept in its place, pointing to `AI_Context.md` — Claude Code specifically auto-loads only a file with that exact name at the start of every session, so removing it entirely would silently stop that auto-loading for any teammate (or future session) using Claude Code, without benefiting teammates on other tools (most don't look for `AI_Context.md` by name either — it has to be opened deliberately, same as any other doc).
- **Consequences:** Everyone should treat `AI_Context.md` as the single source of truth and edit only that file — `CLAUDE.md` is intentionally kept minimal so it can never drift out of sync with it. Every cross-reference in `docs/` and inline code comments that used to say "see `CLAUDE.md`" was updated to say "see `AI_Context.md`" as part of this change, except historical, dated entries elsewhere in this log (e.g. D8, D9) which describe the state of the repository as it was at the time and are left unedited on purpose.
- **Source:** Direct instruction from Jillian, this session.

### D12 — Venue Staff catalogue scope and data model
- **Status:** Decided for this implementation (user-agreed and implemented, 2026-09-13/14); not a change to the team's live backlog.
- **Decision:** Build VS06A venue entry plus catalogue list/detail backend reads. Store required address, one overall capacity, supported layouts, required operating information and optional additional information. There is no booking request, approval state or per-layout capacity in this slice. Address and the application-generated UUID identify the venue; no venue-name column is added.
- **Capacity:** One integer from 1 to 50,000 inclusive, shared by all selected layouts. The form starts at 50 and Venue Staff can change it. This is a frontend initial value, not a database default.
- **Layouts:** API DTOs use `VenueLayout` with `classroom`, `theatre`, `boardroom`, `banquet`, `exhibition`; the entity uses `List<String>` and the database uses `supported_layouts text[]`. These are the implemented choices accepted in this task, not evidence of independent team/customer sign-off on an exhaustive vocabulary. At least one selection is required; duplicate/null selections are rejected.
- **Validation:** Address is trimmed, required and limited to 500 characters by the service; it is not checked for real-world existence. Operating information is nonblank free text describing days/hours and closures. Structured opening-hour calculations remain outside this slice.
- **Source:** User instructions and approvals in this task, 2026-09-13/14; `VenueService`, `VenueLayout`, `VenueCreatePage` and `SCHEMA.md`.

### D13 — Local V5 revision during venue development
- **Status:** Decided and completed, 2026-09-13.
- **Decision:** Use `V5__venue_catalogue_logistics.sql` for the required capacity range, multiple layouts and nonblank operating information. V5 had been applied by an earlier test run; the user authorised revising it because it was unshared and there was no local data. Every application table was checked for rows before the local schema was rebuilt, and Flyway reapplied V1-V5 successfully.
- **Consequences:** This was an explicitly authorised local-development reset, not permission to edit migrations applied to shared databases. Further changes after sharing/application elsewhere must use a new migration. V2 was not edited.
- **Source:** User authorisation and PostgreSQL/Flyway verification in this task; V5 migration.

### D14 — Venue catalogue API and form interaction
- **Status:** Decided and implemented, 2026-09-13/14.
- **Decision:** `POST /api/venues` creates a catalogue record immediately and returns 201 with its UUID and Location header. `GET /api/venues` lists records ordered by address then UUID; `GET /api/venues/{id}` returns details or 404. DTOs are named `CreateVenueDto` and `VenueDto`, using `supportedLayouts` consistently across frontend/backend.
- **Frontend:** `/venue-staff/catalogue/new` reuses shared fields, layout chips, buttons, cards and `apiClient`. While editing, invalid entered values receive field-specific errors; Save validates all required fields and displays all errors together beside those fields. Failed saves preserve input. Successful saves navigate to `/venue-staff/catalogue`, issue a fresh GET and show a dismissible success banner with a light green border and translucent green background. Catalogue cards display address, generated ID and the recorded basic characteristics.
- **Source:** User instructions in this task; `VenueController`, `VenueCreatePage`, `VenueCataloguePage`, `VenuePages.test.tsx`; `docs/venue-api.md`.
- **Known limits:** Accessibility/facilities remain outside the implemented slice and use existing empty database defaults. Venue API errors currently collect field messages in `message` rather than populate `missingFields`. Frontend role gates do not secure the API: Q2 remains open and no backend Venue Staff authorization has been implemented. The later combined checklist is not fully satisfied, and the Venue Staff home still contains outdated skeleton copy.

### D15 — Merging `main` (venue catalogue + CI) into `feature/eo1-eo2-eo15-event_request`: `V5` renamed to `V6`
- **Status:** Decided and completed, 2026-09-15.
- **Decision:** Both branches independently claimed migration version 5 — this branch's `V5__accessibility_none_option.sql` (already applied on shared/tested databases, confirmed via `flyway_schema_history` on 2026-09-13) and `main`'s `V5__venue_catalogue_logistics.sql` (D13, applied only to that branch author's local, unshared database). D13 already establishes that an unshared, unapplied-elsewhere migration may be renumbered; `main`'s file was renamed to `V6__venue_catalogue_logistics.sql` on merge rather than renumbering the already-shared one. SCHEMA.md's internal `V5` references for venue capacity/layouts/operating information were updated to `V6` to match; D13's own text is left as the historical record of what the file was called when that work happened, per this log's convention for dated entries (see D2's note by example).
- **Consequences:** Anyone who already pulled `main`'s `V5__venue_catalogue_logistics.sql` onto a local database must rename their local file to `V6__...` (or reset via `docker compose down -v`) before pulling this merge, or Flyway will report a checksum/version mismatch.
- **Source:** Merge of `origin/main` into this branch, this session; `flyway_schema_history` inspection confirming which `V5` was actually shared.

## Awaiting Team Confirmation

### Q1 — Is the planned velocity (77.5 points/sprint) realistic?
- **Raised by:** repository/backlog inspection, 2026-09-12.
- **Context:** Backlog `Estimation` sheet: 310 total points ÷ 4 sprints = 77.5 planned velocity/sprint. Actual (Done-only) velocity to date: 8 points. The Week 3 course slide deck's own worked example uses a velocity of 15 for a comparably-sized team — 77.5 is roughly 5× that.
- **Options:** (a) accept as-is and re-baseline after Sprint 1's actual velocity is known; (b) trim Release-1 scope further; (c) add more/shorter sprints; (d) treat points here as closer to "ideal hours" than relative story-point sizing and reconcile the scale.
- **Owner:** team / Product Owner role.

### Q2 — Real auth/session mechanism — narrowed, not fully resolved
- **Raised by:** repository inspection, 2026-09-12. **Partially answered** by D6a, 10/9/2026: the team has decided to defer real account creation/credential-based login and use a role-selector ("Login as ___") for now, so this no longer blocks starting Sprint 1's role-specific pages.
- **Still open:** (a) whether the selected role needs to be enforced **server-side** during this interim phase (e.g. sent as a header/claim the backend validates) so that `AU04`'s server-side-enforcement acceptance criteria can be met even without real credentials, or whether server-side enforcement is intentionally deferred alongside real auth, with only front-end routing separating role views for now; (b) which real auth mechanism (Spring Security session vs. JWT vs. other) gets adopted once account creation is tackled.
- **Options for (a):** a lightweight "acting-as-role" header/claim validated by the backend even pre-auth; or explicitly treat `AU04`/`DEV05`'s full server-side enforcement as deferred to the same later slice as real accounts, and say so in the DoD for any Slice-0 story that touches role-specific data.
- **Owner:** whoever picks up `AU04`, in consultation with the team — resolve (a) before writing role-gated endpoints, since retrofitting server-side checks later is more work than building them in from the start.

### Q3 — `event_request_status` has no literal "draft" value
- **Raised by:** repository/backlog cross-check, 2026-09-12.
- **Context:** `SCHEMA.md`/`V2__init_tables.sql` defines `event_request_status` as `{pending, approved, rejected, cancelled}`. EO01's acceptance criteria require a distinguishable "Draft" status, and the customer briefing's process description (§5) also mentions "submitted," "under review" as distinct stages not currently present as enum values.
- **Options:** add `draft` (and possibly `under_review`) to the enum via a new migration; or model "draft" as `event_id IS NULL` plus a separate boolean/flag — needs a decision before EO01/EO02 can be implemented correctly.
- **Owner:** whoever picks up EO01/EO02, in consultation with the team (this is a schema change, so flag it before writing the migration).

### Q4 — Booking-conflict detection has no DB-level protection yet
- **Raised by:** `SCHEMA.md` (team-acknowledged), cross-referenced with `DEV06`/`VS08`/customer briefing.
- **Context:** `SCHEMA.md` gap #1: "No overlap protection on `venue_bookings` — two confirmed bookings can hold the same venue at the same time. Currently to be prevented in the frontend." The customer briefing explicitly names double-booking as a real current failure; `DEV06` (Sprint 3) calls for "concurrency-safe checks."
- **Options:** application-level check with appropriate locking/transaction isolation; a DB constraint/exclusion constraint (e.g. Postgres `EXCLUDE USING gist` on a time-range column, requiring the `btree_gist` extension); or both.
- **Owner:** whoever picks up `DEV06`, in consultation with the team — resolve before Slice 2 (Sprint 3) starts, not during it.

### Q5 — Several backlog rows have stale Scope vs. Priority/Points/Sprint values
- **Raised by:** backlog inspection, 2026-09-12.
- **Context:** `VS06`, `VS09`, `VS13`, the first `AT05` row, `AT06`, `AU01A/B/C`, `AU03`, `AU05` are marked `Out of Scope` but still carry a Priority/Points/Sprint value (harmless to the live array-formula views, which correctly exclude Out-of-Scope rows, but confusing to a human reader).
- **Options:** clear the stale values on Out-of-Scope rows, or leave as-is with a documented convention that Scope, not the other columns, is authoritative.
- **Owner:** whoever is doing backlog hygiene (Product Owner role) in the Google Sheet — Claude Code cannot edit the sheet directly.

### Q6 — Duplicate story IDs: `AT05` and `AT08` each used twice
- **Raised by:** backlog inspection, 2026-09-12.
- **Context:** `AT05` appears as both an Out-of-Scope row and a separately-worded "Added" row; `AT08` similarly. This will confuse traceability (story ID → AC → test case → code) once these are implemented.
- **Options:** rename one of each pair (e.g. `AT05` → `AT05A`) or explicitly retire the superseded row.
- **Owner:** Product Owner role, in the Google Sheet.

### Q7 — `DEV11` (frontend app shell) marked "In Progress" — status vs. repository reality
- **Raised by:** repository/backlog cross-check, 2026-09-12.
- **Context:** Backlog `Sprint Backlog 1` sheet shows `DEV11` (`Assigned To`: Bev) as "In Progress." The repository's `frontend/src/App.tsx` is still the unmodified Vite starter template as of the same date.
- **Options:** the work exists elsewhere (another branch, an uncommitted local copy) and needs pushing; or the status label needs correcting to "To Do."
- **Owner:** Bev, or whoever owns frontend-shell work, to confirm and reconcile.

### Q8 — No numeric NFR targets given by the customer
- **Raised by:** Week 1 Customer Briefing review.
- **Context:** Performance, scalability, and reliability requirements are stated qualitatively ("reasonable time," "anticipated growth over three years") with no numbers.
- **Options:** propose team-internal targets for test design (e.g. "common read operations under 2s in the dev/test environment") and label them explicitly as team assumptions, not customer requirements, in any test plan or NFR document.
- **Owner:** team, before writing performance-related tests.
