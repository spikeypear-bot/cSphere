# ConnectSphere — Product Context

Status: living document. Primary source of truth for user stories, priorities, points, and sprint/person assignment is the live Google Sheet backlog: `https://docs.google.com/spreadsheets/d/19VlaC36ed5ABgLAl9t8qhi5Eh4zO7V2JjtxRyCrNPjI/edit?usp=sharing` (edited collaboratively by the whole team, in real time) — this file summarises and traces to it, and should be re-checked against it rather than assumed stale-safe. The repository's local `.xlsx` snapshot of this backlog is being retired; don't treat its absence as an error.

## 1. Product Vision & Operational Problem

ConnectSphere Event Services is a regional (Southeast Asia, based in Singapore) event-planning and venue-services organisation running conferences, seminars, workshops and similar events, with ~500 internal staff. It currently coordinates a typical event across email, spreadsheets, shared calendars, messaging apps, online forms, and manual documents (VERIFIED, Week 1 Customer Briefing). This produces: incomplete/duplicated/hard-to-track requests, divergent spreadsheet copies of the same event, uncertainty over whether an event/venue/equipment arrangement is approved, incomplete or outdated information reaching Venue Staff, accidental double-booking of the same venue or equipment, insufficient turnaround time between events, missed last-minute changes, cancelled events still holding reservations, and no reliable way to see who changed what and when.

ConnectSphere wants a single, custom-built Event Planning and Venue Booking System to replace this. IS212's Release 1 is not the whole system described in the briefing — it is the **20 core features** named in the Week 4 Project Instructions, delivered through a real scrum process over four ~2-week sprints (per the current backlog's `Estimation` sheet).

## 2. Stakeholder & Role Matrix (VERIFIED, Week 1 Customer Briefing)

| Role | Internal/External | Responsibility |
|---|---|---|
| Event Organiser | External | Supplies event requirements; communicates with ConnectSphere; owns their own event requests. |
| Event Coordinator | Internal | Assigned to coordinate an event; reviews requests; main internal point of contact; searches/requests venues and equipment; confirms the event. |
| Venue Staff | Internal | Maintains venue records; decides venue booking availability/approval. |
| Technical Support Staff | Internal | Maintains equipment records; decides equipment availability/reservation. |
| Attendee | External | Registers for and attends events where registration is enabled. |

Cross-cutting: **Authenticated User** — the login/session/RBAC behaviour every role above needs (backlog role, not a sixth application role). **Not application roles**: Developer / Scrum Master / Product Owner label *Technical*/*Process* backlog stories about the team's own engineering and scrum practice (see `AI_Context.md` § Users). System Administrator is not named anywhere in the source material — do not add one without team/customer confirmation.

## 3. Release Scope & Traceability

The 20 core features (VERIFIED, Week 4 Project Instructions) are listed with priority in `AI_Context.md`. The backlog (`User Stories` sheet, 114 rows as of 2026-09-12) maps every candidate story from the wider Week 1 briefing against these 20 features, recording a `Within Scope, Included` / `Out of Scope` / `Added` call with a one-line rationale per row — that column is the MoSCoW-equivalent priority mechanism for this project and should be treated as authoritative over any restatement here.

**Explicit deferrals** (Out of Scope in the current backlog): event programme/multi-session recording; supporting-document attachments; in-app comments/discussion; tentative venue holds; venue setup/turnaround-time as a distinct trackable feature; Event-Coordinator "portfolio" browsing; rejection appeals; user guides; role-based dashboards; most reporting/export; attendance-vs-registration distinction; waiting lists; on-site technical-staff assignment; recurring/similar-event reuse; event categories.

## 4. End-to-End Workflows (VERIFIED, customer briefing §5, cross-checked against backlog epics)

1. **Event request creation & submission** — Event Organiser drafts a request (EO01, "Event Request Management" epic) with name, purpose, description, proposed date/time, expected attendance, venue requirements, accessibility needs, equipment requirements, registration needs; can save incomplete and return later; submission is blocked while required fields are missing (EO02).
2. **Coordinator assignment** — an Event Coordinator is assigned as the request's internal point of contact (EC epics; reassignment supported per EC-NEW1 when staff responsibilities change).
3. **Review & clarification** — the Coordinator reviews the request, may request clarification from the Organiser, and approves/rejects/returns it (EC01/EC02).
4. **Venue selection & availability checking** — the Coordinator searches/filters venues against the event's requirements (EC04) and checks suitability against capacity/accessibility/facilities (EC05); Venue Staff maintain the venue catalogue (VS01/VS06A/VS06B/VS07/VS18) and its availability.
5. **Venue booking** — the Coordinator submits a booking request (EC03); Venue Staff approve/reject with a reason (VS02/VS03/VS04), with booking-conflict detection preventing double-booking (VS08, DEV06 — see business rules).
6. **Equipment allocation** — the Coordinator requests equipment (EC07); Technical Support Staff check availability (TS01) and reserve it (TS02), updating operational status as needed (TS03) without that update itself creating/cancelling a reservation.
7. **Event confirmation** — once required arrangements are complete, the Coordinator confirms the event (EC08); the Organiser can then view confirmed arrangements (EO04).
8. **Attendee registration** — where enabled, Attendees register during the defined period and subject to capacity (AT01–AT04, AT07), receiving confirmation (AT03).
9. **Event changes & cancellation** — the Organiser can request a change to date/attendance/venue/equipment requirements (EO03) or request cancellation (EO07); the Coordinator reviews the change's impact on existing arrangements (EC-NEW3).
*(Interim, team decision 10/9/2026: there is no real login yet. The main page offers a "Login as [Role]" button per role — Event Organiser, Event Coordinator, Venue Staff, Technical Support Staff, Attendee — which routes straight into that role's pages. Account creation and credential-based authentication are deferred to a later slice; build role-specific pages against this selector for now, not against a login form.)*

10. **Status, conflict resolution, audit, notifications** — the event's status is a single, consistently-derived value visible to all relevant users (EC-NEW2); RBAC/data-scoping (AU04) ensures each role sees only what it's entitled to; a notification system informs users of assignment, approval/rejection, booking decisions, changes, and cancellation (EO09, EC-NEW4, VS17, TS11, second AT05); an internal audit log (DEV07, PROPOSED — not yet implemented, no schema support beyond `created_by`) is intended to record who changed what and when.

## 5. Detailed User Stories

The full, current set of 114 stories (with IDs, roles, epics, scope calls, priority, points, sprint, and full acceptance criteria) lives in the backlog workbook's `User Stories` sheet — that is the traceable source of record for grading purposes (Deliverable 1 in the Week 4 Project Instructions). Below are the stories most relevant to the first implementation slice (Sprint 1, and the immediately-following core flow), rewritten in the required template. Status labels below reflect how each story was arrived at, not the software's implementation status.

---

**ID:** EO01
**Persona:** Event Organiser
**As a / I want / so that:** As an Event Organiser, I want to save an incomplete event request as a draft so that I can review or complete it at a later time.
**Acceptance criteria (Given/When/Then, from backlog):**
- Given an event request with missing required information, when the Organiser selects "Save as Draft", then the system saves it with Draft status.
- Given a saved request, when the Organiser views their requests, then any incomplete saved request is clearly labelled Draft.
- Given a saved draft, when the Organiser reopens it, then all previously entered information is displayed and editable.
- Given changes to an existing draft, when the Organiser saves again, then the same draft is updated, not duplicated.
- Given required information is still missing, when the Organiser attempts to submit, then submission is blocked and the missing fields are indicated.
- Given all required information is present, when the Organiser submits, then the request's status changes from Draft to the appropriate submitted status and becomes available for review.
**Validation/error cases:** submission attempted with missing required fields must be blocked with a specific, field-level message (not a generic error).
**Dependencies:** none (foundational story).
**Priority / Points / Sprint:** High / 3 / Sprint 1.
**Source:** Week 1 Briefing §5 Step 1; Week 4 core feature "Draft Event Requests".
**Status:** VERIFIED (backlog "Within Scope, Included").

---

**ID:** EO02
**Persona:** Event Organiser
**As a / I want / so that:** As an Event Organiser, I want to submit a completed event request containing event name, purpose, dates, expected attendance, venue and accessibility requirements so that ConnectSphere has everything needed to begin review.
**Acceptance criteria:** derived from Week 4's exact field list for "Event Request Creation" — name, purpose, description, proposed date/time, expected attendance, venue requirements, accessibility needs, equipment requirements, registration needs where relevant.
**Validation/error cases:** all named required fields must be present and individually validated (e.g. attendance is a positive integer; dates form a valid range) before submission succeeds.
**Dependencies:** EO01 (draft mechanism), if the team wants drafts to precede every submission.
**Priority / Points / Sprint:** High / 3 / Sprint 1.
**Source:** Week 4 core feature "Event Request Creation".
**Status:** VERIFIED (backlog "Within Scope, Included"; wording improved by backlog review to name exact fields).

---

**ID:** EO15
**Persona:** Event Organiser
**As a / I want / so that:** As an Event Organiser, I want to view my event requests and their statuses so that I can identify which requests require completion and track those that have been submitted.
**Acceptance criteria:** the Organiser sees only requests belonging to their own organisation; each request shows its current status per the approved status model.
**Validation/error cases:** an Organiser must not be able to view another organisation's requests (data-scoping — ties to AU04).
**Dependencies:** AU04 (role/data-scope enforcement).
**Priority / Points / Sprint:** Medium / 3 / Sprint 1.
**Source:** Week 4 core feature "Event Status Management"; customer briefing NFR (security).
**Status:** VERIFIED (backlog "Within Scope, Included").

---

**ID:** AU04
**Persona:** Authenticated User (cross-cutting)
**As a / I want / so that:** As an authenticated user, I want to view and perform only the actions permitted for my assigned role so that I cannot access functions outside my responsibilities.
**Acceptance criteria (refined, from Sprint Backlog 1 threaded comment on cell E22 — treat as authoritative over any shorter paraphrase):**
- Sees only navigation, pages, and actions relevant to their assigned role; cannot open a protected page or perform a protected action outside it; is shown a clear access-denied message when they try.
- An Event Organiser can view/manage only event requests belonging to their own organisation; an Event Coordinator only events assigned/authorised to them; Venue Staff only venue records/bookings/venue-related event information they manage; Technical Support Staff only equipment/reservations/technical-support information relevant to them; an Attendee only their own registration and confirmed event information they're entitled to see.
- Cannot gain access merely by entering a direct page URL, or by changing role/organisation/ownership/assignment data to widen access.
- **Server/API validation enforces role and data-scope restrictions, not only the UI.**
- Unauthorised access attempts or sensitive access changes are recorded in the audit trail if audit logging is implemented.
**Validation/error cases:** direct API calls outside a role's scope must be rejected server-side even if no UI path exists to trigger them.
**Dependencies:** none for the rule itself, but almost every other story depends on it for correctness.
**Priority / Points / Sprint:** High / 8 / Sprint 1.
**Source:** Week 4 core feature "User Authorisation and Authentication"; customer briefing NFR (security).
**Status:** VERIFIED (backlog "Added" — the briefing implied this but no single Week-1 story named it explicitly; the backlog review added it because Week 4 requires it). **Implementation approach changed by team decision, 10/9/2026**: real account creation/credential login (`AU01A/B/C`, `AU02`, `AU03`, `AU05`) is deferred; for now "role" comes from a "Login as [Role]" selector on the main page, not a verified account. Whether server-side enforcement still applies during this interim phase is open — see `docs/decision-log.md` Q2 — resolve before implementing this story.

---

**ID:** VS06A / VS06B
**Persona:** Venue Staff
**As a / I want / so that:** (VS06A) As Venue Staff, I want to record a venue's capacity, supported layouts, and operating information so that Event Coordinators can identify venues that meet an event's basic logistical requirements. (VS06B) As Venue Staff, I want to record a venue's accessibility provisions and facilities so that Event Coordinators can assess whether it meets attendees' access and equipment needs.
**Acceptance criteria (refined, threaded comments E15/E16 on Sprint Backlog 1):** VS06A — venue created with a unique identifier, positive-integer capacity, one or more supported layouts, and operating days/hours; cannot save if any of these is missing; confirmation shown on save. VS06B — one or more accessibility provisions and facilities recorded (or an explicit "none" value where valid); recording these does not alter capacity, layout, operating info, or booking status.
**Validation/error cases:** capacity must be a positive whole number; required fields enforced before save.
**Dependencies:** none (foundational venue-catalogue story); splits the original VS06.
**Priority / Points / Sprint:** High / 3 each / Sprint 1.
**Source:** Week 4 core feature "Venue Catalogue".
**Status:** VERIFIED — this is a deliberate INVEST split of the original VS06 (kept in the Full Backlog Table, greyed out, for traceability).

---

**ID:** DEV02
**Persona:** Developer (technical/NFR story)
**As a / I want / so that:** As a Developer, I want to design a normalised relational schema covering events, venues, equipment, bookings, and users so that the system maintains consistent, non-duplicated data.
**Acceptance criteria (refined, threaded comment D24 on Sprint Backlog 1):** schema supports all implemented Release 1 workflows; includes the core entities (users/organisations, event requests/events, venues, venue availability/bookings, equipment, equipment status/reservations, attendee registrations where implemented); every table has a defined primary key; relationships via documented foreign keys; normalised to at least 3NF unless an exception is documented; a schema/data dictionary documents tables/attributes/keys/relationships/status values; constraints prevent invalid values (e.g. negative capacity/quantity) where feasible; a version-controlled SQL schema/migration set can build the DB from a clean environment; DB setup steps documented in the README.
**Validation/error cases:** n/a (infrastructure story) — verified by inspecting the migration files and `SCHEMA.md` against this checklist.
**Dependencies:** none; almost everything else depends on it.
**Priority / Points / Sprint:** High / 8 / Sprint 1.
**Source:** Grading rubric — "System design"; Week 4 Lab (C4/architecture).
**Status:** VERIFIED — **repository-confirmed as largely satisfied**: `V2__init_tables.sql` + `SCHEMA.md` exist, define PKs/FKs/enums/indexes, and document known gaps. Backlog marks this **Done**; treat the "Done" status as accurate for the schema-design AC, but note the acknowledged gaps (no booking-overlap constraint, no audit table) are tracked separately, not silently closed.

---

**ID:** DEV11
**Persona:** Developer (technical/NFR story)
**As a / I want / so that:** As a Developer, I want to set up the shared front-end application shell (routing, layout, authentication-aware navigation, and API client) so that every screen has a consistent foundation to build on.
**Acceptance criteria (refined, threaded comment D26 on Sprint Backlog 1):** frontend installs/starts via documented commands; has central routing for implemented pages and placeholders for approved Release 1 journeys; shared page layout; authentication-aware navigation that changes between signed-out/signed-in states and (once role is known) shows role-appropriate navigation, **without relying on hidden UI as the actual security control** — the backend/API remains responsible for access control; a shared API client/service layer with consistent base-URL/environment config and consistent handling of success, validation-error, unauthorised/expired-session, and unexpected-failure responses; an agreed folder structure for components/pages/assets/API code; usable at agreed desktop and mobile viewport sizes; build completes in the agreed environment; README documents install/start/routing/env/connection steps.
**Validation/error cases:** n/a (infrastructure story).
**Dependencies:** none technically, but almost every user-facing story needs it.
**Priority / Points / Sprint:** High / 8 / Sprint 1.
**Source:** Grading rubric — "Working software", "Code quality".
**Status:** **OPEN QUESTION** — the backlog currently marks this **In Progress**, but `frontend/src/App.tsx` in the repository is still the unmodified Vite starter template as of 2026-09-12. Confirm with the team whether this work exists elsewhere (another branch, uncommitted locally) before assuming any of it is done; see `docs/decision-log.md`.

---

*(Remaining stories — EC/VS/TS/AT/AU/DEV/SM/PO series — are fully specified with acceptance criteria on the backlog's `User Stories` sheet and traceable by ID; do not re-transcribe them here as the sheet changes. Pull the specific story's row when implementing it.)*

## 6. Business Rules & Edge Cases

See `AI_Context.md` § "Critical Business Rules & Validation Rules" for the consolidated list (venue suitability vs. capacity, booking-conflict detection, equipment-availability formula, draft→submit gating, single consistent status model, RBAC/data-scoping, registration capacity/period, equipment-status independence from reservations). Key edge cases called out by the customer briefing that Release 1 must at least not silently mishandle: attendance growth invalidating a previously-suitable venue; a date change creating a new venue/equipment conflict; an extension affecting turnaround time before the next booking; a cancelled event's reservations not being released automatically unless a rule says so.

## 7. Data Model / Domain Glossary

Authoritative source: `backend/src/main/resources/db/migration/SCHEMA.md` (VERIFIED). Do not restate table-by-table detail here — read that file. Summary of status enums (VERIFIED): `event_status` {confirmed, cancelled, completed}; `event_request_status` {pending, approved, rejected, cancelled}; `equipment_request_status` {processing, approved, rejected}; `equipment_status` {available, in_use, damaged, maintenance, retired}; `venue_booking_status` {pending, confirmed, changed, rejected, cancelled}. Note the customer briefing's process description (§5) mentions statuses like *draft*, *submitted*, *under review* that do not yet appear as enum values anywhere in the schema — **PROPOSED / OPEN QUESTION**: confirm with the team whether `event_request_status` needs additional values (e.g. `draft`, `under_review`) to match EO01/EO02's acceptance criteria, since the current enum only has 4 values and none of them is literally "draft".

## 8. Non-Functional Requirements

See `AI_Context.md` for the consolidated, source-labelled list. All numeric targets (response-time thresholds, concurrent-user counts, growth-rate assumptions) are **PROPOSED placeholders only if invented by the team** — the customer briefing gives no numbers, so do not present any number as a customer requirement without saying it's a team assumption.

## 9. API / Page / Component Recommendations

**REPOSITORY NOT YET INSPECTED FOR THIS** beyond confirming no real controllers/screens exist yet. Recommendations should follow the existing `mock` package's layering (Controller → Service → Repository → DTO/Mapper → Entity) once real domain work starts, and the frontend should follow whatever app-shell structure DEV11 establishes (routing, layout, auth-aware nav, API client) — verify DEV11's actual repository state first per the open question above.

## 10. Test Strategy & Traceability

Per Week 4 material: derive test cases from each story's acceptance criteria using the 5-step approach (visualise workflow → happy path → cross-cutting quality expectations → negative testing → boundary testing). The chain the course grades is **user story → acceptance criteria → test case → test class → code**; be ready to walk that chain for any story picked at random in the Week 13 Q&A. `DEV03` (backlog, High/8/Sprint 3) is the story that establishes this — "unit and integration tests that trace to each core feature's acceptance criteria." No test files exist in the repository yet beyond the default Spring Boot smoke test (`ConnectSphereApplicationTests`) and no frontend test framework is installed — a traceability matrix (story ID → AC → test file/case) should be built incrementally as stories are implemented, not retrofitted at the end.

## 11. Risks, Technical Debt, Assumptions, Open Questions

- **OPEN QUESTION**: planned velocity (77.5 points/sprint, from `Estimation!E8`) is roughly 5× the Week 3 slide deck's own worked example (velocity 15) for a comparable team — confirm this is realistic before treating the 4-sprint plan as fixed.
- **OPEN QUESTION**: several backlog rows show `Out of Scope` with a stale Priority/Points/Sprint value left over (VS06, VS09, VS13, first AT05, AT06, AU01A/B/C, AU03, AU05) — harmless to the live formulas but worth a cleanup pass.
- **OPEN QUESTION**: `AT05` and `AT08` each appear as two different rows sharing one ID — resolve before these IDs are used in test-case or C4-diagram traceability.
- **OPEN QUESTION**: `DEV11` (frontend app shell) is marked "In Progress" in the backlog but the repository's frontend is still the unmodified Vite template — reconcile before Sprint 1 review.
- **Technical debt (acknowledged in `SCHEMA.md` itself)**: no DB-level venue-booking overlap protection yet (frontend-only prevention is not sufficient per DEV06/the briefing); no audit-trail table beyond `created_by`; no equipment check-in/return flag; `event_requests` nullable start/end vs. `events` non-null start/end needs an explicit approval-path rule.
- **Assumption to confirm**: no numeric performance/scalability targets were given by the customer — any SLA-like number used in tests or NFR docs must be labelled PROPOSED, not customer-verified.
