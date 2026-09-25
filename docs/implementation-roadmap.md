# ConnectSphere — Implementation Roadmap

Status: living document, sequenced from the live backlog's Sprint 1–4 assignment (Google Sheet — see `AI_Context.md`). Re-check the sheet before starting any slice below; sprint contents, points, and `Assigned To` names can change.

## How this maps to the backlog

The team has already planned 4 sprints (`Estimation` sheet: 310 total points, ~2 weeks each, 2026-09-07 → 2026-11-01) — well ahead of the Week 12 deadline. This roadmap sequences **within** that plan by dependency, and separates "build now" from "defer unless required," per Week 4 grading emphasis on coherent end-to-end workflows over isolated features.

**Build now (Release 1, matches the 20 core features):** everything currently `Within Scope, Included` or `Added` on the `User Stories` sheet.
**Defer unless the team/customer explicitly brings it in scope:** everything listed as `Out of Scope` in `AI_Context.md` § "Explicit Out-of-Scope / Deferred" (event programme/sessions, document attachments, comments, tentative holds, dashboards, most reporting, appeals/user guides, waiting lists, on-site staff assignment, recurring-event reuse, event categories).

## Slice 0 — Foundations (Sprint 1, in progress)

**Goal:** a running, testable skeleton that every later slice builds on: schema, role-scoped pages, CI, and a frontend shell — plus the first real vertical feature (draft → submit an event request).

**Team decision, 10/9/2026 (Haoming, Calynn):** real account creation/credential login is deliberately deferred. The main page instead offers a "Login as [Role]" button per role, routing straight into that role's views. Build Slice 0 against this selector, not a login form — see `docs/decision-log.md` D6a. Whether the selected role must also be checked server-side during this phase is still open (Q2) — resolve before wiring up role-gated endpoints.

**Superseded in part on `feat/auth`, 2026-09-21/22 (Hong Ming, not team-ratified):** real credential-based login now exists — stateless JWT access tokens plus rotating, revocable refresh tokens — which answers Q2(b) and makes Q2(a) moot, since the role arrives in a verified claim rather than a selector. `AU01A/B/C`, `AU02`, `AU03` and `AU05` are effectively closed by it and the backlog needs updating if the team accepts. See `docs/decision-log.md` D19 for what is built, what is deliberately missing, and what must be ratified before merge (the entry was numbered D17 until the 2026-09-22 rebase onto `main`, which had independently claimed that number). **`AU04`'s server-side half is now done too** (D19 stage 7 + D20, 2026-09-22): every endpoint carries a role rule in `SecurityConfig.authorizeHttpRequests`, and `/api/event-requests/**` is scoped by the access token's `organisation` claim rather than the client-supplied `X-Organisation` header — a valid Organiser token for one organisation could previously read another's requests by naming it in that header. The `AU04` row below is therefore stale on the backend; what remains is the **frontend**, which still uses D6a's role selector and sends no `Authorization` header at all, so the React app is 401 against its own backend until a login page lands.

**Skeleton consoles scaffolded 2026-09-12** (Jillian, so every role has a real page to build on before the rest of the team assigns themselves their own stories): every "Login as [Role]" button on the main page now routes to a real page, not a "coming later" notice. Event Organiser's pages are real (EO01/EO02/EO15, above); Event Coordinator (`/coordinator`), Venue Staff (`/venue-staff`), Technical Support Staff (`/technical-support`), and Attendee (`/attendee`) each open a console listing that role's not-yet-built stories as clickable placeholder pages — plus three more placeholder pages under Event Organiser itself (EO03 change request, EO07 cancellation, EO04 confirmed-arrangements view) that no one has picked up yet. Each placeholder page names its backlog story ID(s), the Release 1 feature it maps to, and the backend package it should live in (following the `eventrequest` package's layering) — no page calls the backend yet, by design, so no one's future story is pre-built for them. Config lives at `frontend/src/features/<role>/*Features.ts`; the shared rendering is `frontend/src/components/FeatureSkeletonPage.tsx` and `RoleConsoleHomePage.tsx`. **Story IDs on these pages are this session's best mapping from `docs/product-context.md`, not a line-by-line read of the live sheet — re-check exact IDs/ACs/`Assigned To` there before starting real work on any of them.**

| Story | Goal | Likely files/modules | Data | API/UI | Tests | Risk | DoD |
|---|---|---|---|---|---|---|---|
| DEV02 (Done per backlog) | Normalised relational schema | `backend/.../db/migration/V2__init_tables.sql`, `SCHEMA.md` | Already exists | n/a | Schema reviewed against DoD checklist (threaded comment D24) | Acknowledged gaps (no booking-overlap constraint, no audit table) — don't treat as silently resolved | Verify checklist item-by-item against current migration before calling this truly closed |
| DEV04 | CI pipeline runs tests on every commit | new `.github/workflows/*.yml` (or equivalent) | n/a | n/a | At least one real backend test executed by the pipeline | No CI exists yet — greenfield; keep it minimal (build + test) first | Pipeline runs from a fresh clone, fails on a failing test, documented in README |
| DEV01 | C1/C2 (Context/Container) diagrams | `docs/architecture/` (new) — Structurizr DSL or C4-PlantUML | n/a | n/a | Reviewed against threaded-comment E28 checklist (consistent with implemented DB/access-control/deployment, no invented components) | Diagrams drawn from imagination rather than the real implemented shape | Diagram matches what's actually built, version-controlled, updated when architecture changes |
| AU04 | Role-scoped access (no real accounts yet — see D6a) | frontend: "Login as [Role]" selector + role-aware routing; backend: TBD whether the selected role is validated server-side this phase (Q2) | `users.role` (exists, but not yet tied to a real session) | Role-selector page; every role-specific screen/endpoint | Negative tests: at minimum, one role's pages cannot be reached from another role's selector path; server-side test once Q2 is resolved | Q2 unresolved — decide before wiring role-gated endpoints, or scope this story down to front-end routing only for Slice 0 | Selector routes correctly per role; DoD scoped explicitly to whatever Q2 decides (front-end-only vs. also server-enforced) |
| EO01 (✅ Done, verified 2026-09-12) | Save event request as Draft | `eventrequest/{entity,dto,mapper,repository,service,controller}` — see `EventRequest.java`, `EventRequestService.java`, `EventRequestController.java`; frontend `features/organiser/EventRequestWizardPage.tsx` + `useEventRequestDraft.ts` | `event_requests` table; schema resolved via `V3__event_request_draft_support.sql` (added `draft` enum literal, relaxed required columns to nullable) and `V4__event_request_accessibility_needs_as_text.sql` (`accessibility_needs` → `text[]`, see D10) | `POST /api/event-requests` (draft), `PUT /api/event-requests/{id}` (update draft) | 10 backend unit tests (`EventRequestServiceTest`) + 2 controller slice tests (`EventRequestControllerTest`) + frontend `EventRequestWizardPage.test.tsx`/`apiClient.test.ts` — **13/13 backend, 10/10 frontend passing** | Resolved — status-enum gap fixed by V3; accessibility_needs mapping took 3 attempts before landing on the text[] approach (D10) | **Met.** Live-verified via `docker compose up --build` + curl: create incomplete → 201 draft; reopen; edit via PUT; submit while incomplete → 422 with exact missing-fields list |
| EO02 (✅ Done, verified 2026-09-12) | Submit a completed event request | same domain as EO01 | same | `POST /api/event-requests/{id}/submit` | Covered in `EventRequestServiceTest` (submit blocked while incomplete lists exact missing fields; submit succeeds and transitions draft→pending; already-submitted request can't be resubmitted) | Resolved | **Met.** Live-verified: PUT to complete required fields → 200 same ID; submit → 200 status=pending; resubmitting an already-pending request is rejected |
| EO15 (✅ Done, verified 2026-09-12) | View my event requests & statuses | same domain | same | `GET /api/event-requests` scoped by `X-Organisation` header; frontend `features/organiser/OrganiserHomePage.tsx` | `EventRequestServiceTest` list-scoping test + cross-org GET returns 404 (deliberately same exception as not-found, not a distinct 403) | Full AU04 (server-side role/account enforcement) is still open (Q2) — today's scoping is by the `X-Organisation` header only, not a verified session; acceptable for the interim role-selector phase per D6a but flagged as a gap, not a finished RBAC story | **Met for the interim scope.** Live-verified: list only returns the calling organisation's requests; a different `X-Organisation` value cannot fetch another organisation's request (404) |
| VS06A / VS06B | Venue catalogue: core + accessibility/facility fields | new `venue` domain | `venues` table (exists) | `POST`/`PUT /venues` | Required-field validation; positive-integer capacity | None significant — schema already supports this | AC-complete per refined threaded-comment ACs (E15/E16) |
| VS07, VS18 | Update / view venue characteristics | same domain | same | `PUT`/`GET /venues/{id}` | Read scoped to authorised Venue Staff | Depends on AU04 | Update reflected immediately on read |
| TS02, TS03 | Reserve equipment; update equipment operational status | new `equipment` domain | `equipments`, `equipment_logs`, `serialised_equipments` | `POST /equipment/{id}/reserve`, `PUT /equipment/{id}/status` | Availability-window query test (per `SCHEMA.md`'s documented SQL); status update does not itself alter a reservation | Equipment availability formula is non-trivial (overlap query) — get this right early, it's reused everywhere | AC-complete per refined threaded-comment ACs (E20/E21) |
| DEV11 | Frontend app shell | `frontend/src/` — routing, layout, auth-aware nav, API client | n/a | n/a | Build succeeds; responsive at agreed breakpoints | **Backlog says "In Progress" but `App.tsx` is still the Vite template as of 2026-09-12** — reconcile with whoever owns this (Bev, per `Assigned To`) before assuming any of it exists | AC-complete per refined threaded-comment ACs (D26) |

**Demo script for Slice 0:** run `docker compose up --build -d` + frontend dev server → from the main page, select "Login as Event Organiser" → create a draft event request, save, reopen, complete required fields, submit → select "Login as Venue Staff" → view/update a venue's characteristics → select "Login as Technical Support Staff" → reserve equipment for a date range and confirm the availability figure decreases → CI badge shows green on the PR that added all of this.

**Risks:** whether role-selection needs server-side enforcement this phase is still open (Q2) — decide before building role-gated endpoints, or explicitly scope Slice 0's `AU04` to front-end routing only. (The status-enum gap that previously blocked EO01/EO02 is resolved — see the EO01 row and D7/D10 in `decision-log.md`.)

## Slice 1 — Coordinator Review, Venue Search & Booking (Sprint 2 per backlog)

Goal: an Organiser's submitted request can be picked up, reviewed, matched to a venue, and booked — the heart of the core workflow.

Backlog stories: EO09, EO19, EC01–EC05, VS01–VS05, VS15, VS16, VS20, AU03, AU05, AU06, DEV05, DEV09.

Key dependency: **DEV05** (RBAC enforced at the API layer) generalises Slice 0's `AU04` work — do this before building coordinator/venue-staff endpoints that need finer-grained scoping, and note DEV05 is where real, verified-role server-side enforcement would land regardless of how Q2 is resolved for Slice 0. **Booking conflict detection is a Slice-2 concern (VS08/DEV06 below), not required to complete Slice 1** — but design VS02–VS04's booking-request flow so a conflict check can be inserted without rework.

## Slice 2 — Confirmation, Conflict Detection, Equipment Requests (Sprint 3 per backlog)

Goal: an event can be fully confirmed once venue + equipment are settled, with real conflict protection.

Backlog stories: EO04, EC07, EC08, EC-NEW1, EC-NEW2, VS08, VS09, VS13, VS25, TS01, TS06, DEV03, DEV06, DEV07, DEV12.

**DEV06 (concurrency-safe booking checks) is the highest-risk item in the whole roadmap** — the schema has no DB-level overlap constraint (`SCHEMA.md` gap #1), and the customer briefing explicitly calls out double-booking as a real operational failure today. Do not defer this past Slice 2; a demo that can still double-book a venue under concurrent requests will read as a core-feature failure, not a polish gap.

## Slice 3 — Changes, Cancellation, Registration, Notifications (Sprint 4 per backlog)

Goal: the remaining core-feature surface — event changes/cancellation, attendee registration, and notifications — closing out the 20 core features.

Backlog stories: EO03, EO07, EO10–EO12, VS17, VS24, TS09, AT01–AT08, AU01A/B/C, AU02, DEV08, DEV10.

## Build now vs. defer unless required (recap)

**Build now:** all stories listed in Slices 0–3 above (everything currently `Within Scope, Included`/`Added`).
**Defer:** event programme/sessions, document attachments, in-app comments, tentative holds, dashboards, most reporting/export, appeals, user guides, waiting lists, on-site technical-staff assignment, recurring-event reuse, event categories — do not build these without an explicit backlog scope change.

## Sensible MVP/demo path

If time runs short, the smallest coherent demoable path is: **Slice 0's EO01/EO02/EO15 + VS06A/06B/07/18 + TS02/03, with role-scoping via the "Login as [Role]" selector actually wired up** (server-side too, if Q2 lands on that side) — i.e. an Organiser can create and submit a request, a Venue Staff member can maintain venue data, and Technical Support can reserve equipment, each only reachable through their own role's selector path. That alone demonstrates the core request→resource-check loop end-to-end and is honest about what's real, rather than a wider but shallower feature set.

## Definition of Done for each slice

Per `AI_Context.md` § Definition of Done — every story above is only "done" once its acceptance criteria are met, role/data-scoping is enforced server-side, tests exist and pass, the code is reviewed/explainable, conventions are followed, docs are updated, and it's demoable end-to-end (or the limitation is documented).

## VS16 development fixture

For Venue Staff / Event Coordinator work on the shared booking-detail component,
[the VS16 seed guide](vs16-manual-testing.md) provides an opt-in local venue, event
and pending booking under `backend/dev/seed/`. This enables manual component testing
before Coordinator workflows exist; it does not implement them or resolve backend
authorization. VS02 now reuses the existing booking-detail route.

## VS02 review flow — implemented with ownership limitation (2026-09-25)

Venue Staff Booking Approvals now lists pending bookings from the VS-only queue
endpoint and opens the existing read-only booking details. Queue context preserves
the return link across refreshes and suppresses catalogue pagination; returning
fetches current pending records. Details show the current status if it has changed.
Coordinator booking submission and VS03/VS04 decisions are separate work.

Last verification: 62 Venue Staff/routing frontend tests and 15 booking API
integration tests passed, with build/lint and seeded browser flow checks. The
pending-list test file contributes 14 of those frontend cases. See the
[test inventory](venue-booking-api.md#automated-test-inventory) and
[run instructions](vs16-manual-testing.md#vs02-automated-tests).

Full “venues I manage” acceptance remains deferred: no staff-to-venue ownership
relationship exists. Queue role access is enforced; shared detail endpoint access
is unchanged. This implementation is not a claim that ownership acceptance is met.
