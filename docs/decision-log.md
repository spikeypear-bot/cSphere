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

### D16 — Added a Code-level class diagram (`docs/class-diagram.puml`), kept flat/no inheritance
- **Status:** Decided for this implementation (Jillian-agreed and added, 2026-09-19); not yet reviewed/agreed by the rest of the team — revert freely if the group disagrees (see the commit this entry ships with).
- **Decision:** Added `docs/class-diagram.puml`, a PlantUML class diagram covering the Java classes actually implemented so far (`EventRequest`, `Venue`, and their enums), per IS212 Week 5's guidance to keep class/sequence diagrams as versioned, diffable text next to the code rather than an exported image, and to update both in the same PR going forward. Deliberately has zero inheritance: neither `EventRequest` (creation vs. amendment, via the `requestType` discriminator) nor `Venue` currently has behaviour that diverges by "kind" at a shared call site, so per the same material's rule — inheritance only for a real is-a relationship that also needs polymorphism — a flat class per entity is correct, not under-designed.
- **Consequences:** This is scoped to the classes that exist in code right now; it is not a replacement for `SCHEMA.md`'s full ER diagram (which already covers every DB table, including ones with no Java entity yet). Flagged for a future revisit if EO03 (amendment) or the `User`/role model end up needing real per-type behaviour — see the file's own inline notes for what to check before reaching for inheritance there.
- **Source:** IS212 Week 5 lecture (`Materials/Week5/Week5-CommunicatingDesign_Collaborating.pdf`) and lab materials; direct instruction from Jillian, this session.

### D17 — EO01/EO02/EO15 UX enhancements: widened ACs, not yet team-approved
- **Status:** Implemented (Jillian-directed and approved for building, 2026-09-19); the AC wording widening is a backlog change and has **not** been applied to the live Google Sheet — Claude Code cannot edit it directly (see "Product Backlog Spreadsheet" in AI_Context.md). Flag to the team before treating these as official ACs.
- **Context:** Jillian asked for UX/event-planning best-practice research and was open to widening ACs for "wow factor." Four enhancements were picked from a shortlist backed by external sources (multi-step form autosave/UX research, approval-dashboard status-visualisation research).
- **Decision:**
  1. **Live completion tracker (EO02):** the wizard's Review step shows a running checklist of required fields (reusing `EventRequestService`'s exact required-field list, mirrored client-side) that updates as the organiser types, instead of only surfacing what's missing after a blocked submit attempt.
  2. **Visual status timeline (EO15):** the organiser's request list shows status as a horizontal stepper (Draft → Submitted → Approved, with Rejected/Cancelled as a distinct terminal state) reusing the wizard's own `StepIndicator` styling, instead of a flat colour badge alone (badge kept alongside it, not replaced).
  3. **True autosave + local backup (EO01):** the wizard now saves a field change automatically ~1.5s after the person stops typing (previously only saved on step-change/exit/submit), and mirrors unsaved field state into `localStorage` keyed by the draft's id so a dropped connection or accidental tab close doesn't lose typed input; the local copy is cleared on a confirmed successful save.
  4. **Last-edited time + completion % (EO01/EO15):** each draft card on the organiser's list shows "edited X ago" (relative time from the new `updated_at` column, D17/V7) and a completion-percentage ring computed from the same required-field list as (1).
- **New backend column:** `event_requests.updated_at` (`V7__event_request_updated_at.sql`), set by `EventRequestService` on every save/submit — see SCHEMA.md.
- **Consequences:** None of these change what's required to submit — they only change how visibly that requirement is communicated before and during editing, and add a genuinely missing last-modified timestamp. Should be proposed to the team as AC additions to EO01/EO02/EO15 in the Google Sheet, not assumed as already agreed scope.
- **Source:** Direct instruction from Jillian, this session; UX research cited in the same session's response (multi-step form autosave/UX and approval-dashboard status-visualisation sources).

### D18 — Cross-organisation data leak found and fixed in EO01's local draft backup (D17 item 3)
- **Status:** Found, fixed, and verified against the real running app, 2026-09-19.
- **Context:** While live-testing EO01-TC4/EO15-TC3 (cross-org access denial) with a real second organisation and a real request id (not a placeholder), the wizard displayed the *other* organisation's event name instead of blocking access, even though the server correctly returned 404.
- **Root cause:** D17's local-backup key (`connectsphere.draft-backup.<id>`) was scoped only by request id. This browser's `localStorage` is shared across every organisation "logged in" on it in turn (no real account/session isolation yet — D6a), so a stale backup left by one organisation's own session answered the load-failure fallback for a *different* organisation hitting a correctly-blocked cross-org 404 on the same request id.
- **Decision:** Every backup read/write/clear is now keyed by organisation *and* id (`useEventRequestDraft.ts`). Added a regression test (`EventRequestWizardPage.test.tsx`) asserting a same-device, different-organisation 404 is never masked by another organisation's backup.
- **Consequences:** This was a client-side-only leak — the backend never exposed the data (confirmed via a direct API call returning 404 both before and after the fix) — but it did undermine EO01/EO15's "cannot view another organisation's draft" guarantee at the UI level on a shared device. Re-verified live: the exact browser sequence that surfaced the leak (Organisation B creates a draft, Organisation A navigates directly to its id) now correctly shows "Event request not found," and 8 other previously-unexecuted EO01/EO02/EO15 test cases were run live in the same session (EO01-TC2/TC3/TC5, EO02-TC2/TC4/TC5, EO15-TC2/TC4) — all passing.
- **Source:** Found via Playwright-driven live browser testing, this session, while filling in EO01/EO02/EO15's test case execution results.
### D19 — Real authentication started on `feat/auth`: Spring Security, stateless, heading for JWT + refresh
- **Status:** **Backend complete (stages 0–7, 2026-09-22); frontend outstanding; NOT team-ratified** — decided unilaterally by Hong Ming on 2026-09-21 while learning the stack. Written as `D17`; renumbered to `D19` when rebasing onto `main`, which had independently claimed `D17` and `D18` — per D15, the entry that was never shared is the one that moves. Flagged here precisely because it runs ahead of D6a and pre-empts Q2(b); the team must ratify it (or reject it) before this branch merges.
- **Context:** D6a deferred credential-based login in favour of the "Login as [Role]" selector, and Q2(b) ("which real auth mechanism — Spring Security session vs JWT vs other") is still open. Building role-gated endpoints on top of an unauthenticated role selector means retrofitting server-side checks later, which D6a's own consequences note is more work than building them in.
- **Decision (proposed):** credential-based login with **stateless JWT access tokens plus rotating opaque refresh tokens**, answering Q2(b) as "JWT, not session". No `HttpSession` — `SessionCreationPolicy.STATELESS`, so the token is the session. CSRF disabled for the token-header API (it protects against browser-auto-attached credentials, which a Bearer header is not) — to be revisited when the refresh token moves into an httpOnly cookie, where the reasoning stops holding.
- **Route chosen:** Spring's **OAuth2 Resource Server** for token validation rather than jjwt plus a hand-written `OncePerRequestFilter`. jjwt is not version-managed by the Boot BOM; Nimbus arrives managed via `spring-security-oauth2-jose`; and no custom `Filter` needs writing, so signature/expiry verification is not hand-rolled. Spring **Authorization Server** was considered and rejected — its model is third-party clients, grants, scopes and consent, which is wrong for one first-party React app with a username/password form.
- **Progress as of 2026-09-22:** Stages 0–6 complete and verified. `config/SecurityConfig` (filter chain + `PasswordEncoder` bean); `user/` slice (`User`, `UserRole`, `UserRepository`, `UserPrincipal`, `AppUserDetailsService`); `DevUserSeeder` with 25 dev accounts. HTTP Basic was temporarily enabled purely so authenticated requests were testable with `curl -u` before tokens existed; **removed in stage 7** (see below). Stage 4 added `user/dto/LoginRequest`+`LoginResponse`, `user/controller/AuthController` (`POST /api/auth/login`, permitAll), the assembled `config/AuthenticationConfig` (`DaoAuthenticationProvider` → `ProviderManager`), and an `AuthenticationException` handler on `ApiExceptionHandler` returning an identical 401 for wrong password, unknown username and blank input alike — live-verified against all 25 seeded accounts. Stage 5 added `spring-boot-starter-security-oauth2-resource-server`, `config/JwtConfig` (HS256 `JwtEncoder`/`JwtDecoder` over a base64 secret from `JWT_SECRET`, with a committed dev fallback in `application-dev.properties`), `user/service/TokenService` (15-minute access tokens claiming `sub`/`iss`/`iat`/`exp`/`username`/`role`/`organisation`), and `oauth2ResourceServer` on the filter chain with a `JwtAuthenticationConverter` that maps the lowercase `role` claim to `ROLE_<UPPER>` — verified by a temporary `hasRole("VS")` probe that Bearer and Basic grant identical authorities (vs1 200 / ec1 403 on both paths), and that a one-character edit to a token yields 401. Stage 6 added `V11__refresh_tokens.sql` (+ a `SCHEMA.md` section), `RefreshToken`/`RefreshTokenRepository`/`RefreshTokenService`, and `POST /api/auth/refresh` + `/logout`. Refresh tokens are opaque 256-bit random values, SHA-256-hashed at rest, single-use, rotating, grouped by `family_id`; re-presenting a rotated token revokes the whole family. Live-verified: rotation returns a new token and kills the old, reuse of a rotated token 401s *and* kills its successor, logout is 204 and idempotent. **Known limitation, inherent to stateless access tokens:** logout revokes renewal only — an already-issued access token stays valid for up to 15 minutes. ⚠️ **`V7` collided with `V7__event_request_updated_at` on `origin/main`** — the number was claimed knowingly while this branch was 14 commits behind. Resolved on the rebase: renamed to `V11__refresh_tokens.sql` (`main` was already at `V10`). Anyone who applied the old `V7__refresh_tokens.sql` to a local database must `docker compose down -v` and re-migrate, or Flyway will report a version/checksum mismatch. Stage 7 is recorded in its own bullet below.
- **Stage 7 (2026-09-22) — authorisation, error shape, tests:** `@EnableMethodSecurity` on `SecurityConfig` (without it `@PreAuthorize` compiles, runs, and does nothing — it fails open). `common/web/RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) return the `ApiError` shape; both are also passed to `oauth2ResourceServer` explicitly, because `BearerTokenAuthenticationFilter` calls its **own** entry point and never reaches `ExceptionTranslationFilter`. An `AccessDeniedException` handler on `ApiExceptionHandler` covers `@PreAuthorize` denials, which are thrown inside the dispatcher and so never reach the filter-chain handler. **HTTP Basic removed** — it sent the password on every request with no expiry or revocation, re-ran BCrypt per request under `STATELESS`, and left two principal shapes (`UserPrincipal` on Basic, `Jwt` on Bearer) for expressions to disagree over. Role rules per D20. `X-Organisation` replaced by the token's `organisation` claim (D20 sequencing). `spring-boot-starter-security-test` added; the suite is green at 134 tests, including a cross-organisation regression test. **Remaining:** the frontend — `apiClient` sends no `Authorization` header, so the React app is 401 against its own backend until a login page replaces D6a's role selector.
- **Login identifier:** `username`, not `email`. Both columns are UNIQUE; this was a free choice and is now fixed by `AppUserDetailsService`.
- **Consequences:** closes `AU01A/B/C`, `AU02`, `AU03`, `AU05` (currently marked deferred/Out of Scope — the backlog needs updating if the team accepts this) and gives `AU04`/`AU06` a real credential to enforce against instead of a self-declared role. It also replaces the frontend "Login as [Role]" screen from D6a. **Known breakage:** `VenueControllerTest` (7 tests) now fails with 401 — it is `@SpringBootTest @AutoConfigureMockMvc`, so it loads the real filter chain, whereas `EventRequestControllerTest` is a `@WebMvcTest` slice and is unaffected. CI will be red until this is resolved; the likely fix is `@WithMockUser` — note `spring-boot-starter-security-test` is **not** in `pom.xml` yet (an earlier draft of this entry said it was) and must be added alongside the other per-feature test starters.
- **Source:** This branch's implementation work, 2026-09-21, verified by a running stack (25 accounts seeded, all five roles authenticating, wrong password rejected).

### D20 — AU04/DEV05 authorisation matrix: which role may call which endpoint
- **Status:** **NOT team-ratified.** Drafted 2026-09-22 on `feat/auth` and now implemented: the role rules in the table live in `SecurityConfig.authorizeHttpRequests`, and organisation scoping for `/api/event-requests/**` comes from the access token's `organisation` claim rather than a client-supplied header. This closes the server-side half of `AU04` for the endpoints that exist today. Depends on D19 being accepted: without a verified role claim there is nothing to enforce against.
- **Context:** D19 gave the application real credentials and a verified `role` claim, but no endpoint is role-gated. Every authenticated user of any role can currently reach every endpoint, and `AU04`'s data-scoping half is still keyed off the client-supplied `X-Organisation` header rather than the token. The roadmap has warned since Slice 0 that retrofitting this is more expensive than building it in, so the table is being written before the rules are.
- **Two distinct rules, deliberately separated.** *Role* ("may a Venue Staff member update venues") is the same answer for everyone holding that role and is decidable from the token alone. *Ownership/data scope* ("may this Organiser see **this** request") depends on the row and is only decidable after a lookup. Role rules belong in `SecurityConfig`'s `authorizeHttpRequests`; scoping belongs in the repository query. Neither substitutes for the other — a correct `hasRole('EO')` still lets one Organiser read another's requests for as long as the scope is self-declared.
- **Proposed matrix** (paths as they exist after the 2026-09-22 rebase onto `main`):

  | Endpoint | Rule | Rationale |
  | --- | --- | --- |
  | `POST /api/auth/login\|refresh\|logout` | `permitAll` | Each carries its own credential; already implemented (D19). |
  | `GET /api/venues`, `GET /api/venues/{id}` | `authenticated()` | Organisers browse venues when planning; Coordinators search them against requirements (EC04). Read is not Venue-Staff-only. |
  | `GET /api/venues/search` | `hasRole('EC')` | EC04's date/time, capacity and facility search is a Coordinator workflow; server-side availability excludes overlapping confirmed bookings. Per D12, free-text operating information is displayed but not parsed into opening-hour availability. |
  | `POST /api/venues`, `PUT /api/venues/{id}` | `hasAnyRole('EC','VS')` | Catalogue authoring is VS07/VS18; widened to Coordinators per open question 1 below. |
  | `/api/event-requests/**` | `hasRole('EO')` **plus organisation scoping from the token claim** | EO01/EO02/EO15. Narrowed from `hasAnyRole('EO','EC')` — see open question 2. |
  | `GET /api/equipment/catalogue` | `hasAnyRole('EO','TECHNICIAN','EC')` | Organisers select equipment types for requests; Technical Support and Coordinators use the catalogue for availability/request workflows. |
  | Other `GET /api/equipment/**` | `hasAnyRole('TECHNICIAN','EC')` | Technical Support check availability (TS01); Coordinators request equipment (EC07) and need to see it. |
  | `POST`, `DELETE /api/equipment/**` | `hasRole('TECHNICIAN')` | Reserving and releasing units is TS02. |
  | `PATCH /api/equipment-requests/{id}/status` | `hasRole('TECHNICIAN')` | TS06 decision; approval additionally requires sufficient reservations covering the full event period. |
  | `/api/mock` | `denyAll()` or delete the package | Demo vertical slice illustrating the MVC convention. It is currently reachable by any authenticated user of any role. |
  | `GET /api/event-requests/queue`, `GET /api/event-requests/{id}/review`, `POST /api/event-requests/{id}/clarifications\|approve\|reject` | `hasRole('EC')` **plus assignment check in the service** | EC01/EC02 (D21). The queue is the caller's own requests only. `POST /api/event-requests/{id}/assign-coordinator` and the queue's unassigned list were removed with ELC-C6: a coordinator can no longer see or pick up an unassigned request, and that path now falls under the blanket rule above (403 for a coordinator). |
  | `GET /api/event-requests/unassigned/coordinators`, `POST /api/event-requests/unassigned/{id}/assign` | `hasRole('ECL')` — covered by the existing `/api/event-requests/unassigned/**` matcher **plus an "unassigned and still open" check in the service** | ELC-C6 (Week 7 customer change 5). The Lead assigns an unassigned request to one Event Coordinator, picked from the list of every `ec` account (id and username only). Allowed while the request is `pending` or `clarification_required` with no coordinator, otherwise 409; a missing coordinator or a user who is not an Event Coordinator is 422. The acting Lead is the token's subject. The ECL-C1 queue now keeps an unassigned `clarification_required` request listed, so it can still be opened and assigned. |
  | `GET /api/event-requests/unassigned` | `hasRole('ECL')`, placed **above** the blanket `/api/event-requests/**` rule | ECL-C1 (Week 7 customer change 5). The unassigned queue: every `pending` request with no coordinator, across all organisations. Event Coordinator Lead only — `ROLE_ECL` is a separate authority, so neither `EC` nor `EO` satisfies it. No data scoping: the list is the same for every Lead. |
  | `GET /api/event-requests/assigned` | `hasRole('ECL')`, placed **above** the blanket `/api/event-requests/**` rule | ECL-C2. Every Event Coordinator with the requests they hold that are still under review (`pending` or `clarification_required`), across all organisations, including coordinators who hold none. Event Coordinator Lead only. Read-only, and no data scoping: the list is the same for every Lead. |
  | `POST /api/event-requests/assigned/{id}/reassign` | `hasRole('ECL')` — the ECL-C2 matcher widened to also cover `/api/event-requests/assigned/**` **plus an "assigned and still under review" check in the service** | ELC-C5 (Week 7 customer change 5). The Lead moves a request from its Event Coordinator to a different one; body `{ "coordinatorUserId": "…" }`. Allowed while the request has a coordinator and is `pending` or `clarification_required`, otherwise 409 (a draft or unknown request is 404). A missing coordinator, a user who is not an Event Coordinator, or the coordinator the request already has is 422. Only the coordinator changes; the organiser gets EO19's notice marked as a reassignment, and neither coordinator is notified (EC-NEW4). |
  | `GET /api/event-requests/unassigned/{id}`, `POST /api/event-requests/unassigned/{id}/reject\|clarifications` | `hasRole('ECL')` — the ECL-C1 matcher widened to `/api/event-requests/unassigned/**` **plus an "unassigned and submitted" check in the service** | ECL-C3. The Lead reads any submitted request (a draft is 404), and may reject or ask for clarification only while it is `pending` with no coordinator; otherwise 409. A first filter before assignment: the coordinator's own `/{id}/review\|reject\|clarifications\|approve` stay `hasRole('EC')`, so the Lead cannot approve. |
  | `POST /api/event-requests/{id}/resubmit`, `GET /api/event-requests/{id}/timeline` | `hasRole('EO')` (blanket rule) **plus organisation scoping** | EO26 (D21). |
  | `GET /api/events/{id}/timeline` | `hasAnyRole('EO','EC')` (existing events rule) **plus organisation or assignment check** | D21: the event's whole journey, filtered by role. |
  | `GET /api/events/{id}/venue-options`, `GET\|POST /api/events/{id}/venue-bookings` | `hasRole('EC')`, placed **above** `GET /api/events/**` **plus assignment check** | EC03 (D21). |
  | `GET /api/events/assigned` | `hasRole('EC')`, placed **above** `GET /api/events/**` **plus scoping to the token's `sub`** | EC09 (Week 7 customer change 5). The caller's own `pending` and `confirmed` events, across all organisations, soonest start first. The coordinator is always the token's subject — there is no parameter to ask for another coordinator's list. The Lead's all-coordinators view is a separate story (ELC-C4). |
  | `/swagger-ui/**`, `/v3/api-docs/**` | dev profile only, or accept 401 | springdoc is on the classpath; under `anyRequest().authenticated()` the docs page 401s, and a browser navigation cannot attach a Bearer token. |

- **Open for the team to settle:**
  1. ~~**Should `EC` be able to create or update venues?**~~ **Settled on this branch, 2026-09-22 — widened to `hasAnyRole('EC','VS')`.** The duplicate `@PreAuthorize` on `VenueService.createVenue` was then removed: the URL rule rejects first, so keeping both meant a pure role check written twice, where a drift would let the narrower one win silently while the annotation still looked load-bearing. `VenueService.update` never carried one, so the two write paths are now consistent. Recorded rather than argued: the backlog story that motivates Coordinator involvement with venues is EC04, which is *searching* them (a read), so nothing in the current stories requires Coordinators to author catalogue entries. Still worth a sentence of team confirmation — if the intent was read-only, this row should go back to `hasRole('VS')`.
  2. ~~**When do Coordinators get access to event requests, and how wide?**~~ **Deferred to EC01/EC02, 2026-09-22 — the row is `hasRole('EO')` for now.** Coordinators are internal (`organisation` = "ConnectSphere") and no Organiser belongs to that organisation, so scoping an EC by their own claim returns an empty list on every call. Granting access that silently shows nothing is worse than granting none: it reads as a working feature. The intended model when EC01/EC02 are built is **assignment-based** — a Coordinator sees the requests assigned to them, not an organisation's worth — which is an ownership rule like the Organiser's, not a second organisation rule, and will need a column linking a request to its handling Coordinator.
  3. **404 or 403 for a cross-organisation read?** EO15 deliberately chose 404 so existence is not leaked. `AU06` asks for a clear message on an unauthorised action. Both are defensible; they should be chosen per endpoint and written down, not decided ad hoc.
  4. **Does `/api/mock` survive Release 1 at all?**
- **Placement:** every row above is a path-and-method rule with no argument logic, so all of them belong in `authorizeHttpRequests` rather than as annotations — one readable list, evaluated before the controller runs. `@PreAuthorize` is reserved for rules that must see a method argument or the principal, which in practice means the organisation-scoped ones.
- **Sequencing:** ~~the `X-Organisation` header must be replaced by the token's `organisation` claim **before** these rules go in.~~ **Done, 2026-09-22.** `EventRequestController` now reads `organisation` from the access token's claim and the header is gone. Verified by reproducing the leak first: a valid `eo3` (Globex Holdings) token returned another organisation's draft in full when `X-Organisation: Acme Pte Ltd` was sent, and returns 404 after the change.
- **Consequences:** implements the server-side half of `AU04` and generalises to `DEV05`. Answers Q2(a) by making it moot — the role arrives in a verified claim, so there is no interim "acting-as-role" question left. Each row needs a negative test (wrong role rejected) to satisfy `AU04`'s DoD; `VenueControllerTest` already has the pattern.
- **Source:** Endpoint inventory taken from the controllers on `feat/auth` after the 2026-09-22 rebase; role responsibilities from `docs/product-context.md` §5 and the EC/VS/TS story rows.

### D21 — EC01/EC02/EO26/EC03: request timeline instead of a chat, clarification loop, venue booking requests

Built on `feature/ec01-ec02-ec03-review-clarification-booking` (2026-09-26), migration **V13**.

- **Why a timeline and not a chat.** Week 4's core features include "request clarification or amendments from the Event Organiser" (Event Review and Approval) but not the briefing's separate "Comments and Discussion" feature (EO18, Out of Scope). So the clarification exchange is structured: every entry is created by a workflow action (submit, assign, clarify, respond, approve, reject, request a venue) in the same transaction as the status change it records. The result is the audit trail EC01/EC02 ask for, and the first slice of DEV07. New kinds of entry (e.g. VS04 rejection reasons, TS09 issue reports) are one `ActivityType` constant each; see its Javadoc.
- **Append-only by database trigger**, not only by convention: EC01 says clarifications and responses "cannot be edited or deleted".
- **Audience per entry type**, filtered in the SQL query: Organisers see their request's journey but not internal planning steps such as which venue was requested.
- **New status `clarification_required`.** "Submitted" and "Under review" both remain `pending` (D7). Approval is refused while waiting on the organiser; rejection is still allowed (e.g. no response).
- **Approval re-checks EO02's submission rules** instead of trusting that nothing changed.
- **Not-assigned coordinator now gets 403, not 409** (`ApiExceptionHandler`), per EC02's refined AC ("access-denied").
- **Notifications for assignment and rejection are now sent after commit**, like approval already was (d1033d8). Before, a failing notification insert surfaced at commit, outside the try/catch meant to swallow it, and failed the whole request; it could also announce a change that was then rolled back.
- **EC03/EC05 rules:** the booking uses the event's own times (no second copy); capacity blocks (attendance equal to capacity fits); missing required facilities block a request; a missing requested accessibility feature needs a written justification for Venue Staff; only *confirmed* bookings overlapping the event block a venue, and back-to-back is not an overlap; operational issues with overlapping affected periods also block a venue; at most one pending/confirmed request per event, enforced under a row lock on the event. `operating_information` remains descriptive text and is displayed but not parsed as a machine-checkable weekly schedule; structured operating hours remain a follow-up.
- **Open, for the team:** (1) Customer Q&A: may a coordinator request a venue that is over capacity or missing a facility, with a justification? Changing the answer touches only `VenueSuitability`. (2) VS03/VS08 must re-run the overlap check when confirming. (3) "Venue Staff responsible for the venue" is not modelled; every VS user is notified.

- **V14 follow-up (2026-09-26):**
  - Per-field clarification questions are stored in `field_questions`, and each question is shown beside its field on the organiser's form.
  - Field values are captured in `field_values` when clarification is asked and when the organiser resubmits. The pair shows the coordinator "what changed since you asked". This is limited to the clarification loop; EO16's general change history stays out of scope.
  - Resubmission now saves the organiser's edits and resubmits in one call. The edits are checked on a copy first, so a refusal changes nothing (EO26's last AC).
  - A coordinator can cancel their own *pending* booking request, with an optional reason. Venue Staff are notified. A confirmed booking cannot be cancelled this way; that is change-request territory.
  - "Already decided" conflicts now say so in plain words.

### D22 — EC-NEW2/EC08/EO04: one status vocabulary, Operational Safety Check before confirmation, organiser arrangements view

Built on `feature/ec08-eo04-ecnew2-event_lifecycle` (2026-10-11, merged with SO-C1–SO-C4 from PR #45 on 2026-10-13), migration **V23** (V22 is SO-C1–SO-C4's). Driven by the Week 7 customer changes (newest document wins).

- **One status vocabulary (EC-NEW2).** `JourneyStatus` (backend `common/status`) is the only place a status gets its label, explanation and "who acts next". Every request and event response carries a `journeyStatus` (`StatusView`), and the frontend shows it as given (`JourneyStatusBadge`, `JourneyStepper`, `StatusFilter`) instead of keeping its own label maps. Stored statuses are unchanged: a `pending` request is shown as *Submitted* until the Lead assigns a coordinator and *Under Review* after; a `pending` event is shown as *Planning*; a `confirmed` event whose end has passed is shown as *Completed*. `datePassed` flags an unconfirmed event whose end has passed, without changing what is stored.
- **New event statuses (V23):** `awaiting_safety_review`, `safety_changes_requested`, `safety_approved`. Planning edits (venue and equipment requests) stay open in `pending` and `safety_changes_requested` (`EventStatus.isPlanningEditable()`).
- **EC08 replaces EO09's direct confirm (Week 7 Change #6).** The coordinator sends the event for review once every prerequisite is met (an approved venue, no pending venue request, no venue affected by unavailability, no equipment still processing, and equipment requested if the organiser listed any). They can withdraw it with a reason while it is awaiting review. They can confirm it only after the Safety Officer approves. The server works out what is possible (`GET /api/events/{id}/lifecycle`; `/readiness` stays the Safety Officer's SO-C3 checklist), so the page shows only the actions the server will accept. `POST /confirm` is the assigned coordinator's again (SO-C4 had made it Safety-Officer-only); straight from Planning it returns 409.
- **How EC08 and SO-C1–SO-C4 fit together (agreed by Jillian 2026-10-13; tell Bev).** Both stories were built in parallel and disagreed: SO-C4 let the Safety Officer's approval confirm the event directly and the coordinator could not confirm; EC08 has the coordinator send the event and confirm it after approval. Week 7 does not say who confirms; it does require approve / reject / *request changes*, with changes sending the event back to planning. Combined: the Safety Officer's checklist, notes, review record and "all items checked" gate (SO-C3/C4) are unchanged. `ReadinessService.review` accepts `approve`, `request_changes` and `reject`, only while the event is `awaiting_safety_review`. It records the review as before and moves the status only through `EventLifecycleService.recordSafetyDecision(...)`: approve → `safety_approved` (the coordinator is notified and confirms), request changes / reject → `safety_changes_requested` with the note as feedback (a note is required). Reject is *return to the coordinator*, not a cancellation. The checklist stays editable in Planning and while awaiting review. The Safety Officer's "planning" list now includes every pre-confirmation stage. Also fixed: `EventReadinessController` compared the role claim with `"so"`, but tokens carry `safety_officer`.
- **Back to Planning.** When a venue becomes unavailable (VS01), every affected event that was awaiting review, safety-approved or confirmed goes back to Planning (timeline `returned_to_planning`, and the coordinator is notified). This is done through a Spring application event (`VenueBecameUnavailable`) so that `AvailabilityService` does not depend on the event package. The listener runs **after the unavailability commits, in its own transaction**. Running it inside would take event locks inside venue/booking locks, which booking approval takes in the opposite order (a deadlock, caught by `VenueUnavailabilityTest`). A safety-approval hold expiry (VS-C3) is not built yet.
- **Who is notified.** On a safety decision the coordinator is told (`event_safety_approved`, or `event_returned_to_planning` with the feedback). On confirmation the organiser gets a status change. Approvers are not recorded per booking or request, so all Venue Staff are notified when the event has an approved venue, and all Technical Support when it has approved equipment (`event_confirmed`). Narrow this once "responsible staff" is modelled.
- **EO04 organiser arrangements** (`GET /api/events/{id}/arrangements`). The response is built field by field, so internal notes, holds, buffers and rejected requests cannot leak. Nothing is shown before confirmation. After confirmation the organiser sees approved venues (with capacity, supported layouts, facilities and operating information), how their accessibility needs are met, equipment merged by item, the safety-approval and confirmation dates, and an "updated since first confirmed" or "being updated" marker (`first_confirmed_at` vs `confirmed_at`). Another organisation's event returns 404.
- **Timeline entries** for event-stage actions store journey codes (e.g. `PLANNING` → `AWAITING_SAFETY_REVIEW`) in from/to status, anchored on the originating request.

### D23 — IS212 Week 9: CI extended with lint, audit, package-once, smoke and browser E2E (no deployment)

2026-10-13. Week 9's lab: "the front half of this pipeline transfers directly: lint, tests, a dependency audit, a smoke test of the running app, and a browser test of a key user journey are all worth adding to your project's workflow". The lecture puts continuous *delivery/deployment* in scope for the exam but not the project ("doesn't score extra marks"), so there are no staging/production jobs.

- **Lint** runs inside `frontend-build`, so it is gated by the existing required check without a settings change. It surfaced one long-standing error (a redundant triple-slash reference in `vite.config.ts`), now fixed.
- **Audit:** `npm audit --audit-level=high` blocks. It found 2 high-severity issues in build tools (`brace-expansion`, `source-map-js`), fixed with a lockfile-only `npm audit fix`. The backend jar gets an SBOM and a Grype scan that **reports but does not yet block**; make it blocking once the first report on `main` is reviewed, so a Spring upgrade is planned, not forced by a red build.
- **Package once, test the package:** `package` builds the jar and the frontend bundle once; `smoke` and `e2e` run those artifacts, not the source (the lab's "tidy-packaging" lesson: checks on source cannot notice that what ships differs from what was tested).
- **Smoke** (`.github/scripts/smoke.sh`): health UP, a bad password gets 401, `eo1` signs in, a signed-in call reaches the database, and an organiser gets 403 from a coordinator endpoint.
- **E2E** (`frontend/e2e/`, Playwright): sign-in, a refused password, and an organiser's draft persisting through a reload. Kept deliberately few. Verified locally against the packaged jar: 3/3, then 9/9 with `--repeat-each=3`.
- **Test-suite fixes from the lecture's flakiness list:** the autosave test's real 1.7 s sleep is now fake timers that pin the exact 1500 ms debounce boundary (a mutation from 1500 to 1000 ms is now caught; the old sleep could not catch it). Four database-backed Spring tests that were missing `@Tag("integration")` now have it, so CI runs them in the integration phase.
- **For the team:** add `audit`, `smoke` and `e2e` to `main`'s required status checks once they have run green there. The vite proxy target is overridable (`API_PROXY_TARGET`) so E2E can run beside a dev backend.

### D24 — Testing and traceability conventions (IS212 Weeks 4, 6, 9)

2026-10-16, branch `test/week9-testing-traceability`. **Why:** the rubric grades "testing and traceability". Its top band reads "comprehensive and traceable … normal, boundary, conflict, and failure scenarios. 100% coverage unless not possible and stated why", and the deliverables say test evidence must let a reader "trace important user stories or acceptance criteria to the relevant tests". Before this, 9 of 53 backend test classes named a story; the Sprint 3 test-case tab had IDs but no content; coverage was measured but never shown.

**Decided:**
- **Every test traces to a story.**
  - Java: a class-level `@Tag("<StoryID>")` per story covered, next to its layer tag.
  - TypeScript: story IDs in the `describe`/`it` title, or a `// @stories` header.
  - CI's new `traceability` job fails if a test file names no story. `.github/scripts/traceability.py` writes `docs/test-traceability.md`, including High-priority stories with no test.
  - The team's 100 test files now trace to 45 stories.
- **Test names cite the AC and the case type**, e.g. `EC08 AC26, AC27 (conflict): …`. Done in depth for EC-NEW2, EC08 and EO04: all 95 ACs trace to automated tests (`docs/test-cases/sprint3-jillian.md`, which also holds their Week 4 test cases for the Sheet). Other owners adopt it for their stories as they touch them.
- **Test-double vocabulary** (dummy, stub, mock, fake) as defined in `docs/testing-strategy.md`, with ConnectSphere examples. No fakes: integration tests use real PostgreSQL.
- **E2E for the critical journeys only**, chosen from High-priority stories:
  - the cross-role golden path (request → assignment → approval → venue → safety check → confirmation → organiser's arrangements);
  - two failure paths: rejection, safety changes requested.

  Decisions are made in the browser; the API is used only for test data outside the journey and for ID lookups.
- **Coverage is published, not gated.** The story's classes are brought to 100% or the exclusion is stated.

**Evidence recorded while doing it (Weeks 6/9: think in mutants; fix flakiness, don't retry):**
- **A test caught a real bug.** EO04's new unmount test caught React's duplicate-key warning on the organiser's event page: two sibling panels both used `key={reloadToken}`. Fixed.
- **Mutation checks:**
  - removing EO04's approved-bookings filter → caught by exactly the AC19 test;
  - removing the print rule → all 43 component tests stayed green and **only** the E2E print step failed (lab steps 27–28: the gate catches what only a user would notice).
- **Two flaky E2E runs, both timing, both fixed by waiting for a condition (never retries):**
  - the wizard's Next saves before moving on, and the test clicked it twice without waiting;
  - a failure-path setup opened the coordinator's page before Venue Staff's approval was saved.

  After both fixes, 18/18 runs passed on a fresh packaged build.
- **Coverage removed dead code instead of testing the impossible:** `formatDate(null)`; a Confirmed check that could never be false; a "no approved venue" branch that confirmation already rules out.
- **EO04 AC25** now marks a Completed event "kept as a record" inside the arrangements card.

**For the team:** add `traceability` (with `audit`, `smoke`, `e2e`) to `main`'s required checks once green. Fill your Sheet test-case lanes in the same format, naming your automated tests in Remarks. Turn any `// @stories` header into AC-level names when you next work on that story.

### D25 — ECL-C7: the Lead's assignment is the single source of truth for coordinator access

2026-10-17, branch `feature/eclc7-coordinator_access_scoping`. An audit of every endpoint an Event Coordinator can call found most were already scoped by assignment (review queue, request decisions, venue options/bookings/cancel, equipment requests, lifecycle, readiness, timelines, My events). It also found five gaps, now closed:

1. **`GET /api/events/{id}` let any coordinator open any event.** It now requires the assigned coordinator; the Lead may view any event (`getForLead`, read-only).
2. **Two event-specific Technical Support reads were open to every coordinator**, unscoped: an event's reservations and an equipment request's availability. Coordinators never use them, so the rule now excludes them (the Safety Officer keeps reservations). Inventory reads such as unit status (TS03) stay open to coordinators; they describe equipment, not anyone's event.
3. **Venue options showed other events' names.** In the conflict list ("Occupied: …"), the reasons ("Already approved for …") and the refusal message ("… overlaps the event's time (Gala Dinner)"), they now read "Booked by another event, 10 Mar 2027, 9:00 AM – 12:00 PM (Singapore time)". Venue Staff's approval conflicts keep names: they are responsible for the venue.
4. **Reassignment could not move an approved event.** It was limited to requests under review and never updated `events.coordinator_id`, so after approval the previous coordinator kept the event. Reassignment now also works for an approved request whose event is not cancelled or finished. It updates the request and the event in **one transaction under the event's row lock**, so access moves at once and an action the previous coordinator submits afterwards is refused. The Lead's *Coordinator assignments* page lists each coordinator's active events (`GET /api/events/by-coordinator`, Lead only, kept separate from ECL-C2's request list so that query stays unchanged) with *View event* and *Reassign*.
5. **A refused coordinator saw raw text with an id.** The 403 message is now plain and identical for everyone ("This is not assigned to you. If you were working on it, the Event Coordinator Lead may have reassigned it…"), and coordinator pages show a "This event isn't assigned to you" panel with a way back.

**Checked against the customer documents (Week 7 > Week 4 > Week 1).**
- **Week 7:** *"The Lead can … assign a suitable Event Coordinator, and reassign events where necessary. Event Coordinators should only be able to manage events assigned to them, while the Event Coordinator Lead should be able to view all coordinator assignments and active events under their supervision. Relevant users should be notified when assignments or reassignments occur."*
- **Week 4** (Coordinator Assignment): *"Events may subsequently be reassigned when staff responsibilities or availability change"*; access depends on the user's *"role and relationship to an event"*.

Therefore:
- **ECL-C5 AC5 was wrong.** It said an approved request cannot be reassigned, which contradicts both documents and ECL-C5's own story text ("reassign an in-progress … event"). Agreed with Jillian on 17 Oct 2026: approved requests whose event is still active can be reassigned. ECL-C5's test is updated accordingly, and **the Sheet's ECL-C5 AC5 needs rewording by its owner**.
- **Backlog gap:** Week 7's "relevant users should be notified" includes the coordinators. Only the organiser is told today; ECL-C5 AC11 defers coordinators to **EC-NEW4**, which is High priority but unbuilt and has no ACs yet. This needs ACs and a sprint slot before Release 1.

**Also fixed (found by the new endpoint matrix):** EC-C2's batch booking validated its input before checking assignment, so a stranger got "Add at least one venue" instead of 403. Assignment is now checked first.

**Notifications** already follow `events.coordinator_id` at send time, so reassigning stops them going to the previous coordinator and sends them to the new one.

**Evidence:**
- `CoordinatorAssignmentScopeTest` (integration): every coordinator endpoint, as a coordinator who is not assigned, must answer 403 with no event data and change nothing (statuses, timeline, notifications, bookings, equipment). It also covers Lead view-but-not-act, unassigned requests, reassignment, totals and conflict privacy.
- Unit tests for scoped lookups and notification routing.
- Component tests for the notice, the Lead overview and reassignment.
- E2E `access-scope.spec.ts`.
- All 23 ACs traced in `docs/test-cases/sprint3-eclc7.md`.

**No database migration.**

### D26 — EC-NEW5: one change log per event, recorded where the change happens

2026-10-17, branch `feature/ecnew5-change_log`. EC-NEW5 extends the D21 request timeline (append-only, audience per entry) into the event's full change log. It does not add a second log.

**Design**
- **Services announce, one listener records.** Venue Staff, Technical Support and Venue Availability services publish an `EventChange(eventId, type, actor, message)`. `ChangeLogRecorder` turns it into an entry on the event's originating request, with the signed-in user as actor when none is named.
  - The listener is synchronous, so the entry is written **in the caller's transaction** (`ActivityService.record` is `MANDATORY`). A refused or rolled-back action leaves no entry.
  - The publisher is optional (setter-injected), so plain unit tests of those services need nothing extra.
  - `VenueBecameUnavailable` (VS-C1) is logged on every affected event by the same recorder.
- **New entry kinds** (no migration; `activity_type` is text):
  - venue: booking approved, rejected, alternative suggested, replaced, another venue added, venue unavailable;
  - equipment: requested, approved, rejected;
  - a virtual **Completed** entry, computed at read time at the event's end once a confirmed event has ended. Nobody performs it, so it has no actor or role and is never stored.
- **Normal or Major is decided on the server** and sent as `significance`. A kind is always Major when the story lists it: reassignment, venue unavailable, booking rejected or cancelled, additional venue, safety rejection or changes requested, returned to Planning. An organiser's resubmission is Major when it changes one of Week 4's important fields (date, time, attendance, venue or equipment requirements). The comparison ignores time-zone notation, number format and list order.
- **UI.** The change log on the event page and the coordinator and Lead review pages gains:
  - "All changes" and "Major only" chips with counts, and a way back from an empty filter;
  - "Jump to latest";
  - a text "Major" badge (not colour alone);
  - "(Singapore time)" next to each exact time;
  - a note for requests that predate the log;
  - "No changes recorded yet.";
  - on the event page, an error with Try again that leaves the rest of the page working.

  The log stays read-only: no edit, delete or revert.

**Checked against the customer documents (Week 7 > Week 4 > Week 1).**
- Week 4 (Event Information Management): *"distinguish between information that may be edited normally and important changes that may affect arrangements already made"*.
- Week 4 (Event Change Requests): *"date, time, expected attendance, venue requirements, or equipment requirements"*.
- Week 1: auditability of who changed what and when.

Therefore:
- **EC-NEW5 AC18's Major list should include Week 4's important fields.** The implementation does; the owner should reword AC18 in the Sheet.
- **Two ACs depend on unbuilt features:** AC11's tentative holds (VS-C3) and AC14's event cancellation. Their types will be added when those stories land; reassignment, unavailability, confirmation and completion are logged today.
- **AC7 "detail edits"** are captured where they can happen. An organiser can edit only while a clarification is open, and those edits appear on the resubmission entry as previous → new values (V14), not as separate entries.

**Evidence:**
- `ActivityServiceTest` and `ChangeLogRecorderTest` (unit).
- `ChangeLogFlowTest` (integration, real PostgreSQL): each kind, who and role, order, failure leaves nothing, the trigger refuses UPDATE and DELETE, audiences, and full history after reassignment.
- `ChangeLog.test.tsx` (component).
- Golden-path E2E steps.
- All 35 ACs traced in `docs/test-cases/sprint3-ecnew5.md`.

**No database migration.**

### D27 — IS212 Week 10: guidance plus enforcement for AI agents

2026-10-23, branch `chore/week10-agent-guardrails`. Week 10 (Agentic AI and the Software Team) distinguishes guidance (instruction files) from enforcement (tests, CI, branch protection), and shows an agent PR (#57) that deleted an assertion while CI stayed green.

**Already in place:**
- Guidance for every tool: `AI_Context.md`, plus `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` and `.github/copilot-instructions.md` pointing to it.
- Enforcement of traceability: the `traceability` job.

**Added:**
1. **`test-protection.py`, in the `traceability` job, on pull requests.** It fails a PR that deletes an existing test file, or leaves one with fewer tests or fewer assertions than on the base branch. It lists every changed existing test file, with before and after counts, on the run summary for reviewers.
   - It is deliberately looser than the lecture's example, which fails on *any* change to an existing test. Our tests legitimately change with the behaviour they check (ECL-C7 and EC-NEW5 both edited existing tests). The weakening that matters is a removed test or assertion.
   - A deliberate removal is approved by a reviewer adding the `tests-reviewed` label: the slides' named human approval gate. Pull-request runs now also trigger on labelling.
   - Checked against the last 30 commits of `main`, ECL-C7 and EC-NEW5: no false failures. Removing one assertion or one test is caught.
   - **Not caught:** an assertion replaced by a weaker one, or a test rewritten to match a misread AC. These stay with human review against the ACs.
2. **PR template** with the five Week 10 questions (delegated, constrained, evidence, review needed, accountable) in an "AI assistance" section, alongside the team's existing PR headings.
3. **`AI_Context.md`:**
   - a "Working with AI agents" section: the guidance-to-enforcement map, a delegation dial for our task types, review-capacity rules, the four kinds of agent-era debt, and the CFO request;
   - Definition of Done lines for accountability and test protection;
   - the header no longer says "for Claude Code" (harness debt: the file serves every tool).

**Team action:** add `traceability` (now including test protection) to `main`'s required checks; otherwise it only warns.

**No database migration.**

## Awaiting Team Confirmation

### D28 — EO-C2: a request must describe a real, future event (supersedes the 22 Sep date-range rule)

2026-10-24, branch `feat/eo-c2-request-date-validation`. While writing EO03's ACs we found that EO02 accepted a request that:
- ends at the moment it starts;
- is dated in the past;
- expects 0 or a negative number of attendees.

The server also **silently truncated a decimal attendance** (`150.5` was saved as `150`).

EO02's ACs never required otherwise, so, following the Week 7 *Guide to Managing Changes* (Scenario 2, a new requirement), EO02 stays Done and the new story **EO-C2** (Sprint 4) carries the rules. This supersedes the "Organiser request date-range validation (2026-09-22)" entry below on two points:
- **equal start and end times** are now refused;
- **past dates** are now refused.

Everything else in that entry stands:
- drafts stay saveable;
- invalid inputs are kept, never cleared;
- the server answers 422 before changing status or `updatedAt`;
- a submission first saves the current fields.

**Rules.** One server method, `EventRequestService.scheduleProblems`, is used by submit, resubmit (EO26) and approve (EC02). So a request whose start passes while it waits cannot be approved either: the coordinator's review lists why (`scheduleProblems`). The rules:
- **Start:** must be strictly after now. An event starting this very minute has already started.
- **End:** must be strictly after the start, compared as moments. `10:00+08:00` and `02:00Z` are the same moment, so that pair has no duration. An end on a later date is valid; venue operating hours are checked later, when a venue is booked.
- **Attendance:** at least 1.

"Now" comes from an injected `Clock`, so the boundaries are tested exactly: now, and one minute either side.

**One message per field.**
- `ApiError` gains `fieldErrors` (omitted when empty). A 422 says what is wrong with each field, in the form's order, and the page shows each message next to its field.
- The frontend checks the same rules as the organiser types, using the server's own wording (`eventRequestValidation.ts`).
- Server messages stay visible until the organiser changes that field.
- When the server refuses, for example because the start passed while the page was open, the wizard returns to the schedule step.

**Whole numbers, everywhere.** `spring.jackson.deserialization.accept-float-as-int=false`: a decimal sent for any whole-number field (attendance, quantities, capacities) is refused with 400 instead of being silently rounded.
- This is global on purpose, because silent truncation is wrong for every such field.
- The full backend suite (584 unit, 557 integration) passed with it.
- The attendance input accepts digits only, so a decimal or sign cannot be typed or pasted.

**Singapore time.** `DateTimeField` now reads and shows Singapore time (GMT+8, no daylight saving), whatever the browser's zone, and says so under each date field. The review step labels times "(Singapore time)". This replaces the earlier "browser-local time" note: an organiser abroad now types the event's local time, not their own.

**Evidence:**
- **Backend:** `EventRequestSubmissionRulesTest` (unit, fixed clock), and `RequestSubmissionRulesFlowTest` (integration: API bypass, decimals, resubmission, approval after the start has passed).
- **Frontend:**
  - `eventRequestValidation.test.ts`;
  - `EventRequestDateRules.test.tsx`, which covers Singapore-time entry, attendance input, the server's per-field refusal, and the clock passing the start on the review step;
  - EO-C2 cases in the wizard, clarification and coordinator review tests.

  These run in both UTC and Singapore time.
- **E2E:** `request-rules.spec.ts`.
- All 8 ACs have automated tests named by AC number.

**Known limit:** shared test fixtures (`FlowSupport`, E2E helpers) use fixed 2027 dates. They will need moving forward before mid-2027; EO-C2's own tests use dates relative to now.

**No database migration.**

### D29 — EO03 + EC-NEW3: event change requests, decided against their real impact

2026-10-24, branch `feat/eo03-ecnew3-change-requests`. The organiser asks to change a submitted request or its event (EO03), and the assigned coordinator decides it (EC-NEW3). This is Week 4 core feature "Event Change Requests" and Week 1 step 12: *"The Event Coordinator determines whether those changes affect venue, equipment, technical-support, registration, or other arrangements."*

**Data (V32).** A new table, `event_change_requests`, modelled on EC-C1's `event_reassignment_requests`.
- **Why not V3's `request_type 'A'`:** a second `event_requests` row per event would break `findByEventId` (the change log, timelines and ECL-C7 access all rely on one request per event), and it would appear in coordinators' review queues as a new request.
- **What a row holds:** the changeable fields before and after, as JSON, so the comparison survives later edits; the reason; the decision; and the impacts acknowledged.
- **One pending change per request**, enforced by a partial unique index, so two simultaneous submissions cannot both succeed.
- **Notifications:** three new types (submitted, withdrawn, decided). Their link `/change-requests/{requestId}` opens the reader's own console, since the same notification can go to a coordinator or to the Leads.

**Rules.**
- **The proposal** is checked by the same rules as submitting a request (EO02 completeness, EO-C2 dates and attendance, 422 per field), and checked again at decision time.
- **"Changed" means changed in meaning**, using the change log's comparison (EC-NEW5): offsets, list order, spaces and `150` vs `150.0` are not changes. A request that changes nothing is refused.
- **When it can be asked:**
  - allowed for a Submitted request, or an event in Planning, Awaiting Safety Review, Safety Changes Requested, Safety Approved or Confirmed;
  - refused once the event has started (start ≤ now);
  - refused for drafts (edit directly) and while a clarification is open (reply instead).
- **Who:**
  - organisers by organisation (AU04);
  - the assigned coordinator decides (ECL-C7);
  - before assignment, the Leads are told and the request waits for the coordinator they assign;
  - a pending change follows a reassignment;
  - the Lead reads everything, read-only.
- **Approval applies every field at once**, to the request and its event, under row locks. When an event in Awaiting Safety Review, Safety Approved or Confirmed has its date, time, attendance, venue or equipment requirements, accessibility or facilities changed, it returns to Planning (`EventLifecycleService.returnToPlanning`, EC08).

**Impact (`ChangeImpactService`).** Recalculated on every view from live data, using the system's own checks:
- `AvailabilityService` for clashes, unavailability, operational issues and opening hours, each with that booking's own setup and turnaround;
- `VenueSuitability` on the event as it would be, for capacity, accessibility and facilities;
- hold and equipment-reservation windows;
- the safety consequence.

Every "attention" item has a stable key, and the coordinator must acknowledge each one. If the impact has changed since they looked, approval is refused (409) and the new impact is shown. The acknowledged items are kept as **follow-ups** on the event page.

**Found while testing:** since VS-C2, every booking stores its own window, so a booking does not follow the event's new time. The impact therefore:
- checks a whole-event booking **at the new time** (could it move there?) and flags it *"booked for the old time; nothing is moved automatically"*;
- leaves a part-day (EC-C2) booking in its window and flags it to be checked;
- treats pending booking requests as non-reserving, consistent with the rest of the system.

**Story gaps (raise in the Sheet):**
- **EO07 (cancellation) is not built.** EO03 AC6 (no change while a cancellation is pending) and AC7 (close on cancellation) wait for it.
- **Registrations (AT01) are not built.** EO03 AC19 and EC-NEW3 AC22 wait for them.
- **EC-NEW3 AC40** (Venue Staff and Technical Support told of approved changes) is VS17's and TS11's work.
- **EC-NEW3 AC9** said pending booking requests clash. They do not reserve anything anywhere else in the system, so they are not counted here either.

**Evidence:**
- **Backend:**
  - `ChangeRequestFlowTest` (16, real PostgreSQL), covering the boundaries: meets vs one minute over, extending into the next booking's setup, closing time, overnight, capacity at the limit;
  - `ChangeRequestNotificationFlowTest` (2, committed);
  - `ChangeRequestServiceTest`, `ChangeImpactServiceTest` and `ChangeFieldsTest` (24, unit, fixed clock).
- **Frontend:** `ChangeRequests.test.tsx` (26).
- **E2E:** `change-requests.spec.ts`.

**Migration:** V32 (new table, three notification types, notification constraint re-declared with them added).

## Awaiting Team Confirmation

### Q1 — Is the planned velocity (77.5 points/sprint) realistic?
- **Raised by:** repository/backlog inspection, 2026-09-12.
- **Context:** Backlog `Estimation` sheet: 310 total points ÷ 4 sprints = 77.5 planned velocity/sprint. Actual (Done-only) velocity to date: 8 points. The Week 3 course slide deck's own worked example uses a velocity of 15 for a comparably-sized team — 77.5 is roughly 5× that.
- **Options:** (a) accept as-is and re-baseline after Sprint 1's actual velocity is known; (b) trim Release-1 scope further; (c) add more/shorter sprints; (d) treat points here as closer to "ideal hours" than relative story-point sizing and reconcile the scale.
- **Owner:** team / Product Owner role.

### Q2 — Real auth/session mechanism — narrowed, not fully resolved
- **Raised by:** repository inspection, 2026-09-12. **Partially answered** by D6a, 10/9/2026: the team has decided to defer real account creation/credential-based login and use a role-selector ("Login as ___") for now, so this no longer blocks starting Sprint 1's role-specific pages.
- **Update 2026-09-21:** part (b) has a **proposed** answer — see D19: stateless JWT with rotating refresh tokens, being built on `feat/auth`. Not yet team-ratified, so Q2 stays open until the team accepts or rejects D17. If accepted, part (a) becomes moot: the interim "acting-as-role" question disappears once the role arrives in a verified token claim rather than a selector.
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


## Organiser request date-range validation (2026-09-22)

> **Superseded in part by D28 (EO-C2, 24 Oct 2026):** equal start and end times and past dates are now refused, and times are entered in Singapore time. The rest of this entry stands.

End must be on or after start, comparing full instants. Equal timestamps, same-day
events, matching times on successive days, and past dates are allowed. No
unavailable-date or booking-conflict rule is introduced.

Reversed ranges retain both inputs and show accessible inline correction messages.
Next and submission are blocked until corrected; Back remains available. The end
input minimum follows start, with explicit validation for typed/restored values.
Display uses browser-local time; the API receives ISO instants.

Incomplete or reversed drafts remain saveable to preserve work in progress. Both
dates are required on submission. The backend returns HTTP 422 for reversed ranges
before changing status or updatedAt. Submission first saves current fields; failed
or in-progress saves prompt a retry instead of submitting older saved values.

Verification: 56 Organiser/Venue Staff frontend tests, 18 event-request service and
controller tests, scoped ESLint, and production build passed. Regression coverage
includes correction, retained selections, equal instants with differing offsets,
past dates, HTTP 422, unchanged rejected drafts, and failed-save submission
protection. Native date-picker appearance was not browser-tested.

## VS01 — agreed replacement workflow (6 October 2026)

The user approved implementing the supplied VS01 overview end to end, including setup/turnaround and coordinator replacement handling. This supersedes earlier deferral notes for setup/turnaround within this feature. Recording unavailability preserves original booking status and flags the booking separately. A single pending replacement is allowed. Only approval of that replacement marks the original `changed`; rejection/withdrawal leaves the original flagged. Pending and approved bookings are considered affected; changed/rejected/cancelled bookings are historical and do not reserve space. See [VS01 documentation](venue-unavailability-api.md).

## VS-C2 / EC-C2 — Independent booking windows and duration snapshots (14 October 2026)

- **Decision:** Retain incoming main's multi-venue bookings and event safety lifecycle, then apply setup/turnaround calculations independently to each booking's own window. Save selected durations per booking; later venue-default changes affect future requests only. Retain the incoming restriction on duplicate active venues for one event.
- **Validation:** Check full occupied intervals during selection, submission, approval and staff timing edits, including opening hours, unavailable periods and operational issues. Batch submission is transactional.
- **Database:** Keep incoming V1–V26 unchanged. V27 backfills duration snapshots without changing booking windows; V28 adds timing review, overlap notification tracking and timing notification types while retaining lifecycle notification constraint branches. Legacy nullable windows fall back to event times. Old unpublished local V23/V24 timing histories require separate reconciliation.
- **Consequence:** Screens distinguish booking windows from advertised event times. Timing edits affect only the selected booking's buffers. Existing overlaps are reported without automatic cancellation. See [implementation and verification](venue-booking-duration-snapshots.md).

## EC10 — Replacement venue rebooking (17 October 2026)

- **Decision:** Coordinators may replace a booking that is affected by venue unavailability or has been rejected/cancelled. A replacement is a new pending booking linked to its source and must use a different venue.
- **Consistency:** Replacement is scoped to its source booking, so other active venue bookings for the same event remain unchanged and do not prevent rebooking. At most one replacement per source may be pending or approved. A rejected/cancelled replacement attempt leaves the source eligible for another try.
- **Approval:** Approval changes an unavailable pending/approved source to `changed`; rejected/cancelled source statuses remain as historical outcomes. Approval updates the event's current venue reference only; it does not change other event details or bypass safety confirmation.
- **Validation:** The existing suitability, occupancy, venue availability, operating-hours and operational-issue checks apply to every replacement request and approval.
## VS-C3 — Time-limited tentative holds (18 October 2026)

- **Decision:** Venue Staff place holds on existing pending booking requests. A
  child attempt-history table preserves re-holding without creating duplicate
  bookings or changing ordinary pending-request semantics. The held interval is
  the booking's complete occupied period using its saved duration snapshots.
- **Lifecycle:** Immediate server-clock expiry controls availability; a 30-second
  configurable worker persists expiry and coordinator notification together.
  Release/expiry leaves the request pending. Conversion atomically approves that
  same booking and consumes only its active, unexpired hold. Later ordinary
  approval after expiry/release is a separate fresh availability decision.
- **Scope:** No anonymous holds, new future-start restriction, expiry-before-start
  restriction, or per-venue staff ownership model. Existing event safety and
  confirmation workflows remain separate. All new `/api/venue-bookings/*/holds`
  and `/api/venue-holds/**` endpoints require Venue Staff; coordinator hold details
  travel only through existing assignment-scoped booking responses.
- **Evidence:** [Design, API and acceptance mapping](tentative-venue-holds.md),
  `VenueHoldFlowTest`, `VenueHoldPanel.test.tsx`, and the browser hold journey.

## DEV15 — Class diagrams generated from the code (18 October 2026)

- **Decision:** Replace the hand-drawn `docs/class-diagram.puml` (D16) with diagrams generated by tests in `backend/src/test/java/com/example/connect_sphere/uml/`, using `plantuml-generator-util` (test scope only). Three levels are written to `docs/class-diagrams/`: `domain` (entities, enums, value types), `full` (every layer, minus mock scaffolding and MapStruct `*MapperImpl`) and `auth` (`user`, `config`, `common.web`, minus `DevUserSeeder` and `ApiExceptionHandler`).
- **Why:** The hand-drawn diagram only covered `EventRequest`, `Venue` and their enums and had fallen behind the code (no `VenueBooking`, `EventSafetyReview`, `User`, equipment). Generating it removes the manual step D16 relied on.
- **Modelling choices:** `ClassDiagrams.tidy()` rewrites the generator's raw output, because a review of the first output found it wrong or misleading in places:
  - **Relationships.** Composition (`*--`), which the generator draws for every final field, becomes a plain association: constructor-injected Spring beans are shared, not owned parts. Composition is kept only for an entity's `@Embeddable` id. Collection fields, which the generator draws as aggregation with a `1` on the owner's end (wrong for a shared enum value or entity, e.g. `EventRequest` → `AccessibilityFeature`), become an association with `0..*` on the element end.
  - **Multiplicities.** Most entities hold links as plain UUID columns, so `DomainClassDiagramTest` reads the foreign keys from the Flyway-migrated database and draws them, with multiplicities from the schema (NOT NULL → `1`, unique → `0..1`). The same data gives the multiplicities of `@ManyToOne` lines and of enum fields (column nullability).
  - **Names and types.** Classes are named by the code: nested types as `Outer.Inner`, because the generator's short names had merged `OrganiserArrangementsDto.Venue` with the `Venue` entity (likewise `Equipment` and `Item`) in the full diagram. Field types keep their type arguments (`List<String>`, not `List`). JPA annotations and table names are dropped; `@Id` shows as `{id}`. Record accessors, which repeat the fields, are dropped.
  - **Domain scope.** `StatusView` (an API response shape) and `OperatingHours` (a static helper) are left out. The test fails when any other class that is neither an entity, an enum nor a listed value type reaches the domain diagram.
  - D16's flat, no-inheritance reasoning still applies; the generator draws whatever the code has.
- **ER diagram:** `EntityRelationshipDiagramTest` writes `docs/er-diagrams/schema.generated.puml` from Postgres's catalog after the Flyway migrations: every table and column (`*` NOT NULL, `<<PK>>`, `<<FK>>`), the Postgres enum types, and a crow's-foot line per foreign key (`|o` when the key columns are unique, `o|` when they are nullable). `flyway_schema_history` and the mock tables are left out. The schema queries are shared with the domain diagram in `DatabaseSchema`. It supersedes the hand-written Mermaid diagram in `SCHEMA.md` as the complete picture; that one stays as a curated overview.
- **Consequences:** Every backend test run rewrites the `.puml` files. After each merge, CI's `commit-diagrams` job commits any change to `main` as `github-actions[bot]`; on a PR, `backend-build` only lists what will change. Only `commit-diagrams` has `contents: write`, and it runs git alone, not the tests or Maven plugins, so the code under test never holds a token that can push. Nobody regenerates or commits the diagrams by hand. This replaced the first version, which failed any PR whose committed diagrams differed from the regenerated ones: a PR then went stale, and failed, every time another class-changing PR merged first, and fixing it needed Docker locally. The trade-off is that a PR's diagram changes show on `main` a few minutes after merge rather than in the PR's diff. A push made with the workflow's own token starts no new run, so the commit does not loop. The old file is kept as `docs/class-diagram.stale.puml` for reference and is no longer updated.
- **Follow-ups (code, not diagram — need their own stories):** `Event`/`VenueEvent` and `VenueBooking`/`VenueBookingRecord` map the same tables; most links are UUID fields rather than object references; `Venue` and `Event` store layouts, facilities and accessibility needs as `List<String>` although `VenueLayout`, `Facility` and `AccessibilityFeature` enums exist (so those enums sit unconnected on the diagram), and `EventSafetyReview.reviewStatus` is a `String`; most entities have no behaviour beyond accessors. VS-C3's tentative hold is a JDBC-read record (`VenueHold`, with `status` as a `String`), not an entity, so the domain diagram shows it as a value type with UUID attributes rather than relationship lines; `venue_booking_holds`'s foreign keys are in the schema itself.

### D30 — SO-C5 + SO-C6: a safety decision names the checklist items it concerns

2026-10-24, branch `feat/so-c5-c6-safety-decisions`. The Safety Officer can already approve, request changes or reject (SO-C4, D22). SO-C5 (reject with feedback) and SO-C6 (request changes) make the feedback specific.

- **Scope is the Safety Officer's decision only.** What the event does next is EC08's and is unchanged: a rejection or a request for changes returns it to the coordinator as Safety Changes Requested (EC08 AC23, EC-NEW2 AC4, D22). A rejection that stops the event, an organiser being told, or the coordinator cancelling are other stories; the team decided not to change them here.
- **A rejection or a request for changes must name at least one of the six checklist items (SO-C3)**, and give a reason (1–2,000 characters after trimming, as before). Unknown items are refused, and an approval names none. The items are stored in checklist order, once each, on `event_safety_reviews.flagged_items` (V34) and on the decision's timeline entry (`flagged_fields`). A rejection is allowed with every item ticked: the ticks record what was arranged, and the Safety Officer may still judge it unsafe.
- **The coordinator sees the decision**: outcome, reason, items (highlighted in the read-only checklist), who and when in Singapore time, for every outcome, through `GET /api/events/{id}/readiness`. The Safety Officer sees the previous decision when the event comes back for review.
- **Two decisions at the same moment** save one: `ReadinessService.review` takes the event row lock and checks the status first (`SafetyDecisionCommitTest`). A refused decision answers 422 as before; the Safety Officer's page then reloads the event so it shows the current outcome.
- **Week 7 Change #6** says changes send the event back to planning; it does not say a rejection must end the event, so nothing in the customer's changes is contradicted.
- **Consequence:** `EventStatus.ACTIVE`, `BEFORE_CONFIRMATION` and `IN_OR_PAST_SAFETY_CHECK` now name the status groups that nine services had written out by hand.

