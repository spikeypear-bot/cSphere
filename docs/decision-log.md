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
  | `POST /api/venues`, `PUT /api/venues/{id}` | `hasAnyRole('EC','VS')` | Catalogue authoring is VS07/VS18; widened to Coordinators per open question 1 below. |
  | `/api/event-requests/**` | `hasRole('EO')` **plus organisation scoping from the token claim** | EO01/EO02/EO15. Narrowed from `hasAnyRole('EO','EC')` — see open question 2. |
  | `GET /api/equipment/**` | `hasAnyRole('TECHNICIAN','EC')` | Technical Support check availability (TS01); Coordinators request equipment (EC07) and need to see it. |
  | `POST`, `DELETE /api/equipment/**` | `hasRole('TECHNICIAN')` | Reserving and releasing units is TS02. |
  | `/api/mock` | `denyAll()` or delete the package | Demo vertical slice illustrating the MVC convention. It is currently reachable by any authenticated user of any role. |
  | `GET /api/event-requests/queue`, `GET /api/event-requests/{id}/review`, `POST /api/event-requests/{id}/clarifications\|approve\|reject\|assign-coordinator` | `hasRole('EC')` **plus assignment check in the service** | EC01/EC02 (D21). The queue is the caller's own requests plus unassigned ones. |
  | `POST /api/event-requests/{id}/resubmit`, `GET /api/event-requests/{id}/timeline` | `hasRole('EO')` (blanket rule) **plus organisation scoping** | EO26 (D21). |
  | `GET /api/events/{id}/timeline` | `hasAnyRole('EO','EC')` (existing events rule) **plus organisation or assignment check** | D21: the event's whole journey, filtered by role. |
  | `GET /api/events/{id}/venue-options`, `GET\|POST /api/events/{id}/venue-bookings` | `hasRole('EC')`, placed **above** `GET /api/events/**` **plus assignment check** | EC03 (D21). |
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
- **EC03 rules:** the booking uses the event's own times (no second copy); capacity blocks (attendance equal to capacity fits); a missing requested accessibility feature needs a written justification for Venue Staff; only *confirmed* bookings overlapping the event block a venue, and back-to-back is not an overlap; at most one pending/confirmed request per event, enforced under a row lock on the event. Facilities cannot be checked automatically because event requests store them only as free text (EC05 follow-up). Venue unavailable periods (VS01/VS05) are not built yet, so they are not checked.
- **Open, for the team:** (1) Customer Q&A: may a coordinator request a venue that is over capacity or missing a facility, with a justification? Changing the answer touches only `VenueSuitability`. (2) VS03/VS08 must re-run the overlap check when confirming. (3) "Venue Staff responsible for the venue" is not modelled; every VS user is notified.

- **V14 follow-up (2026-09-26):**
  - Per-field clarification questions are stored in `field_questions`, and each question is shown beside its field on the organiser's form.
  - Field values are captured in `field_values` when clarification is asked and when the organiser resubmits. The pair shows the coordinator "what changed since you asked". This is limited to the clarification loop; EO16's general change history stays out of scope.
  - Resubmission now saves the organiser's edits and resubmits in one call. The edits are checked on a copy first, so a refusal changes nothing (EO26's last AC).
  - A coordinator can cancel their own *pending* booking request, with an optional reason. Venue Staff are notified. A confirmed booking cannot be cancelled this way; that is change-request territory.
  - "Already decided" conflicts now say so in plain words.

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
