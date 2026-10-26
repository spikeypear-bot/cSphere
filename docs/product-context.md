# ConnectSphere — Product Context

Status: living document. Primary source of truth for user stories, priorities, points, and sprint/person assignment is the live Google Sheet backlog: `https://docs.google.com/spreadsheets/d/19VlaC36ed5ABgLAl9t8qhi5Eh4zO7V2JjtxRyCrNPjI/edit?usp=sharing` (edited collaboratively by the whole team, in real time) — this file summarises and traces to it, and should be re-checked against it rather than assumed stale-safe. The repository's local `.xlsx` snapshot of this backlog is being retired; don't treat its absence as an error.

## 1. Product Vision & Operational Problem

ConnectSphere Event Services is a regional (Southeast Asia, based in Singapore) event-planning and venue-services organisation running conferences, seminars, workshops and similar events, with ~500 internal staff. It currently coordinates a typical event across email, spreadsheets, shared calendars, messaging apps, online forms, and manual documents (VERIFIED, Week 1 Customer Briefing). This produces: incomplete/duplicated/hard-to-track requests, divergent spreadsheet copies of the same event, uncertainty over whether an event/venue/equipment arrangement is approved, incomplete or outdated information reaching Venue Staff, accidental double-booking of the same venue or equipment, insufficient turnaround time between events, missed last-minute changes, cancelled events still holding reservations, and no reliable way to see who changed what and when.

ConnectSphere wants a single, custom-built Event Planning and Venue Booking System to replace this. IS212's Release 1 is not the whole system described in the briefing — it is the **20 core features** named in the Week 4 Project Instructions, delivered through a real scrum process over four ~2-week sprints (per the current backlog's `Estimation` sheet).

## 2. Stakeholder & Role Matrix (VERIFIED, Week 1 Customer Briefing)

| Role | Internal/External | Responsibility |
|---|---|---|
| Event Organiser | External | Supplies event requirements; communicates with ConnectSphere; owns their own event requests. |
| Event Coordinator Lead | Internal | Reviews submitted, unassigned requests; may ask for clarification or reject before assignment; assigns requests to and reassigns requests among Event Coordinators; oversees assignments. |
| Event Coordinator | Internal | Assigned to coordinate an event; reviews requests; main internal point of contact; searches/requests venues and equipment; reads readiness results for assigned events. |
| Venue Staff | Internal | Maintains venue records; decides venue booking availability/approval. |
| Technical Support Staff | Internal | Maintains equipment records; decides equipment availability/reservation. |
| Attendee | External | Registers for and attends events where registration is enabled. |
| Safety Officer | Internal | Reviews operational readiness, records checklist status and approves planned events before confirmation (SO-C1–SO-C4; added by the user-authorised implementation scope extension). |

Cross-cutting: **Authenticated User** — the login/session/RBAC behaviour every role above needs (backlog role, not another application role). **Not application roles**: Developer / Scrum Master / Product Owner label *Technical*/*Process* backlog stories about the team's own engineering and scrum practice (see `AI_Context.md` § Users). System Administrator is not named anywhere in the source material — do not add one without team/customer confirmation.

## 3. Release Scope & Traceability

The 20 core features (VERIFIED, Week 4 Project Instructions) are listed with priority in `AI_Context.md`. The backlog (`User Stories` sheet, 114 rows as of 2026-09-12) maps every candidate story from the wider Week 1 briefing against these 20 features, recording a `Within Scope, Included` / `Out of Scope` / `Added` call with a one-line rationale per row — that column is the MoSCoW-equivalent priority mechanism for this project and should be treated as authoritative over any restatement here.

**Explicit deferrals** (Out of Scope in the current backlog): event programme/multi-session recording; supporting-document attachments; in-app comments/discussion; Event-Coordinator "portfolio" browsing; rejection appeals; user guides; generic role-based dashboards; most reporting/export; attendance-vs-registration distinction; waiting lists; on-site technical-staff assignment; recurring/similar-event reuse; event categories.

**Implementation scope extension (user-authorised; backlog sync outstanding):** the supplied SO-C1–SO-C4 stories are implemented as an explicit exception to the generic role-dashboard/checklist deferral. The canonical live backlog could not be accessed during implementation; this note records the supplied scope without claiming team-wide backlog ratification.

## 4. End-to-End Workflows (VERIFIED, customer briefing §5, cross-checked against backlog epics)

1. **Event request creation & submission** — Event Organiser drafts a request (EO01, "Event Request Management" epic) with name, purpose, description, proposed date/time, expected attendance, venue requirements, accessibility needs, equipment requirements, registration needs; can save incomplete and return later; submission is blocked while required fields are missing (EO02). A saved draft stays with the Organiser. On submission, it becomes pending (shown as **Submitted**) and enters the Event Coordinator Lead's unassigned queue.
2. **Lead triage & coordinator assignment** — the Event Coordinator Lead reviews submitted requests across organisations. Before assignment, the Lead can ask the Organiser for clarification or reject the request; a clarification-waiting request remains available for assignment. The Lead assigns an Event Coordinator as the internal point of contact and can reassign it while it is still under review (ECL-C1–C3, ELC-C5–C6, ECL-C7). After approval creates an event, its assigned Coordinator can ask the Lead to transfer it, and the Lead approves or declines (EC-C1); this is distinct from reassigning an incoming request.
3. **Coordinator review & clarification** — after assignment, the request appears in that Coordinator's review queue and is shown as **Under Review** to the Organiser. The assigned Coordinator may request clarification, approve, or reject it (EC01/EC02). Approval creates the event in Planning and associates it with that Coordinator; it does not confirm the event.
4. **Venue selection & availability checking** — the Coordinator searches/filters venues against the event's requirements (EC04) and checks suitability against capacity/accessibility/facilities (EC05); Venue Staff maintain the venue catalogue (VS01/VS06A/VS06B/VS07/VS18) and its availability.
5. **Venue booking** — the Coordinator submits a booking request (EC03); Venue Staff approve/reject with a reason (VS02/VS03/VS04), with booking-conflict detection preventing double-booking (VS08, DEV06 — see business rules).
6. **Equipment allocation** — the Coordinator requests equipment (EC07); Technical Support Staff check availability (TS01) and reserve it (TS02), updating operational status as needed (TS03) without that update itself creating/cancelling a reservation.
7. **Event confirmation** — once required arrangements are complete, the assigned Coordinator sends the event for safety review. The Safety Officer's persisted operational-readiness checklist must be complete before approval (SO-C3/SO-C4). Approval moves the event to Safety Approved; the assigned Coordinator then confirms it (EC08). The Organiser can then view confirmed arrangements (EO04).
8. **Attendee registration** — where enabled, Attendees register during the defined period and subject to capacity (AT01–AT04, AT07), receiving confirmation (AT03).
9. **Event changes & cancellation** — the Organiser can request a change to date/attendance/venue/equipment requirements (EO03) or request cancellation (EO07); the Coordinator reviews the change's impact on existing arrangements (EC-NEW3).
*(Current implementation: users authenticate with seeded development accounts; role- and organisation-scoped API access is enforced server-side. See `AI_Context.md` for the dev accounts and `docs/decision-log.md` for the settled access rules.)*

10. **Status, conflict resolution, audit, notifications** — the event's status is a single, consistently-derived value visible to all relevant users (EC-NEW2); RBAC/data-scoping (AU04) ensures each role sees only what it's entitled to; a notification system informs users of assignment, approval/rejection, booking decisions, changes, and cancellation (EO09, EC-NEW4, VS17, TS11, second AT05); workflow activity, booking requirement reviews, hold histories and assessment snapshots record who changed what and when; a universal audit log remains separate.

### Safety Officer flow (SO-C1–SO-C4; implementation extension)

- Safety Officers can list all confirmed events, soonest first, and separately see planned events awaiting safety review. Cancelled and completed events are excluded.
- Event details include planning data, venue capacity/facilities and booking status, requested equipment and actual reservations, accessibility and registration needs; missing arrangements are shown as not finalised.
- Each event has six predefined readiness items. Safety Officer checks/notes persist; each item shows its last checker and check time. Event Coordinators may view this checklist and approval status for events assigned to them, but cannot change it.
- A Safety Officer may approve only after all items are checked. Approval changes Planning to Confirmed atomically; rejection records the current review outcome and note without changing the event lifecycle status. Re-review can later replace that outcome. Coordinator confirmation is not a permitted alternative.

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
- An Event Organiser can view/manage only event requests belonging to their own organisation; an Event Coordinator only events assigned/authorised to them; Venue Staff only venue records/bookings/venue-related event information they manage; Technical Support Staff only equipment/reservations/technical-support information relevant to them; an Attendee only their own registration and confirmed event information they're entitled to see; a Safety Officer may view active event readiness and update/approve only through Safety Officer-authorised actions.
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
**Status:** VERIFIED — **repository-confirmed as largely satisfied**: `V2__init_tables.sql` + `SCHEMA.md` exist, define PKs/FKs/enums/indexes, and document known gaps. Backlog marks this **Done**; treat the "Done" status as accurate for the schema-design AC, but note the acknowledged gaps (no database booking-overlap constraint, workflow-specific audit histories rather than a universal audit table) are tracked separately, not silently closed.

---

**ID:** DEV11
**Persona:** Developer (technical/NFR story)
**As a / I want / so that:** As a Developer, I want to set up the shared front-end application shell (routing, layout, authentication-aware navigation, and API client) so that every screen has a consistent foundation to build on.
**Acceptance criteria (refined, threaded comment D26 on Sprint Backlog 1):** frontend installs/starts via documented commands; has central routing for implemented pages and placeholders for approved Release 1 journeys; shared page layout; authentication-aware navigation that changes between signed-out/signed-in states and (once role is known) shows role-appropriate navigation, **without relying on hidden UI as the actual security control** — the backend/API remains responsible for access control; a shared API client/service layer with consistent base-URL/environment config and consistent handling of success, validation-error, unauthorised/expired-session, and unexpected-failure responses; an agreed folder structure for components/pages/assets/API code; usable at agreed desktop and mobile viewport sizes; build completes in the agreed environment; README documents install/start/routing/env/connection steps.
**Validation/error cases:** n/a (infrastructure story).
**Dependencies:** none technically, but almost every user-facing story needs it.
**Priority / Points / Sprint:** High / 8 / Sprint 1.
**Source:** Grading rubric — "Working software", "Code quality".
**Status:** VERIFIED as of 2026-09-12 (later same day) — the app shell now exists: central routing with role gating, shared `AppShell` layout, authentication-aware navigation (signed-out/signed-in and role-appropriate, UI-only per the AC's explicit note — backend remains responsible for enforcement), a shared `apiClient` with base-URL/env config and consistent success/validation/unauthorised/unexpected-failure handling, an agreed component/feature/lib folder structure, responsive layouts, and a documented `frontend/README.md`. Confirm the backlog's status is updated to match.

---

*(Remaining stories — EC/VS/TS/AT/AU/DEV/SM/PO series — are fully specified with acceptance criteria on the backlog's `User Stories` sheet and traceable by ID; do not re-transcribe them here as the sheet changes. Pull the specific story's row when implementing it.)*

## 6. Business Rules & Edge Cases

See `AI_Context.md` § "Critical Business Rules & Validation Rules" for the consolidated list (venue suitability vs. capacity, booking-conflict detection, equipment-availability formula, draft→submit gating, single consistent status model, RBAC/data-scoping, registration capacity/period, equipment-status independence from reservations). Key edge cases called out by the customer briefing that Release 1 must at least not silently mishandle: attendance growth invalidating a previously-suitable venue; a date change creating a new venue/equipment conflict; an extension affecting turnaround time before the next booking; a cancelled event's reservations not being released automatically unless a rule says so.

## 7. Data Model / Domain Glossary

Authoritative source: `backend/src/main/resources/db/migration/SCHEMA.md` (VERIFIED). Do not restate table-by-table detail here — read that file. Summary of status enums (VERIFIED): `event_status` {pending, confirmed, cancelled, completed}; `event_request_status` {draft, pending, clarification_required, approved, rejected, cancelled}; `equipment_request_status` {processing, approved, rejected}; `equipment_status` {available, in_use, damaged, maintenance, retired}; `venue_booking_status` {pending, approved, changed, rejected, cancelled}. The event `pending` state is Planning; SO-C4 approval transitions it to `confirmed`.

## 8. Non-Functional Requirements

See `AI_Context.md` for the consolidated, source-labelled list. All numeric targets (response-time thresholds, concurrent-user counts, growth-rate assumptions) are **PROPOSED placeholders only if invented by the team** — the customer briefing gives no numbers, so do not present any number as a customer requirement without saying it's a team assumption.

## 9. API / Page / Component Recommendations

Domain controllers, services, repositories and role-specific screens are implemented. Follow the existing feature layering and app shell, and inspect current code/API guides before extending a workflow. The `mock` package remains scaffolding, not the domain template.

## 10. Test Strategy & Traceability

Per Week 4 material: derive test cases from each story's acceptance criteria using the 5-step approach (visualise workflow → happy path → cross-cutting quality expectations → negative testing → boundary testing). The chain the course grades is **user story → acceptance criteria → test case → test class → code**; be ready to walk that chain for any story picked at random in the Week 13 Q&A. `DEV03` (backlog, High/8/Sprint 3) is the story that establishes this — "unit and integration tests that trace to each core feature's acceptance criteria." JUnit/PostgreSQL integration tests, Vitest/React Testing Library tests and Playwright journeys are implemented. The generated `docs/test-traceability.md` maps story IDs and tests; `.github/scripts/traceability.py --check` enforces attribution.

## 11. Risks, Technical Debt, Assumptions, Open Questions

- **OPEN QUESTION**: planned velocity (77.5 points/sprint, from `Estimation!E8`) is roughly 5× the Week 3 slide deck's own worked example (velocity 15) for a comparable team — confirm this is realistic before treating the 4-sprint plan as fixed.
- **OPEN QUESTION**: several backlog rows show `Out of Scope` with a stale Priority/Points/Sprint value left over (VS06, VS09, VS13, first AT05, AT06, AU01A/B/C, AU03, AU05) — harmless to the live formulas but worth a cleanup pass.
- **OPEN QUESTION**: `AT05` and `AT08` each appear as two different rows sharing one ID — resolve before these IDs are used in test-case or C4-diagram traceability.
- **RESOLVED (2026-09-12)**: `DEV11` (frontend app shell) is now implemented in the repository (see §5 DEV11 above and `frontend/README.md`) — update the backlog's status to match before Sprint 1 review.
- **Technical debt (acknowledged in `SCHEMA.md` itself)**: no DB-level venue-booking overlap protection yet (frontend-only prevention is not sufficient per DEV06/the briefing); workflow-specific histories now exist; a universal audit log and equipment check-in remain separate; no equipment check-in/return flag; `event_requests` nullable start/end vs. `events` non-null start/end needs an explicit approval-path rule.
- **Assumption to confirm**: no numeric performance/scalability targets were given by the customer — any SLA-like number used in tests or NFR docs must be labelled PROPOSED, not customer-verified.


### Current venue workflow rules — 25 October 2026

Week 7 venue buffers and expiring holds are implemented and supersede the earlier deferral list. The user-authorised [workflow repairs](venue-workflow-repair.md) add private-read restrictions, operational-impact safety checks, live availability impact, different-venue replacement enforcement, period release and durable recovery. Shared VS access is explicitly retained. Accessibility shortfalls require justification and staff review; capacity and facilities remain strict blockers. Backlog updates remain a team action.
