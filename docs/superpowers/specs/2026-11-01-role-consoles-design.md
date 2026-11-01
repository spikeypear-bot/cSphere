# Role consoles: side nav + dashboard for every role

Date: 2026-11-01 · Status: approved in conversation, pending doc review

## Intent

Technical Support (TS) already has a console: a side navigation and a dashboard
landing page that shows "what needs your attention" as panels. Every other role
lands on a row of buttons and a grid of "Not built yet" placeholder cards. The
user wants every role to get the TS-style console, the placeholder pages gone,
and pages built from shared components instead of long per-page CSS. This is
the first step of a wider frontend beautify + refactor.

## Goals

- Every role gets a side nav and a dashboard, looking and behaving like TS.
- The TS dashboard's building blocks become shared components any role uses.
- The placeholder ("skeleton") system is removed entirely.
- Nothing that works today becomes unreachable: every current route stays
  reachable from the nav or from the page that links to it today, and every
  API call in use today is still made.

## Non-goals (later, separately)

- Redesigning the venue map (Venue Staff's map goes on its dashboard as-is).
- The 5 backend endpoints the frontend never calls (see Appendix).
- The wider refactor list: merging the five fetch hooks, one date/time module,
  splitting the giant pages, CSS consolidation beyond the pieces moved here.
- Any backend change.

## Anti-goals

- Six dashboards that each look different (the reason for piloting first).
- A changed TS look. TS must look and behave exactly as before.
- A config-driven "generic dashboard" engine. Each role's dashboard is a plain
  page composed from the shared pieces.

## Constraints

- Scope: `AI_Context.md` line 95 lists "generic dashboards/role-based overview
  screens" as out of scope (backlog VS19/TS10/AT10 Out of Scope). The user
  brings role consoles in by hosting each on a reopened story. The pilot PR
  amends line 95 to say role consoles are delivered within each role's
  existing story, as TS09 did; VS19/TS10/AT10 stay out of scope.
- Statuses: show the server's `journeyStatus` via `JourneyStatusBadge` (AI_Context D22); never add a status label map.
- Times in Singapore time.
- Tests: follow AI_Context "Writing or changing a test — every time" — line 1
  `// @stories <IDs>`, titles `"<StoryID> AC<n> (<case>): …"`, never invent AC
  numbers (ask the user; without them use `"<StoryID> (<case>): …"`). A moved
  or merged test file keeps all its existing story tags.
- CI runs lint, vitest with coverage, build, traceability. All must stay green.

## Approaches considered

- **A. Lift TS pieces into shared components, pilot one role, then the rest in
  parallel. (Chosen.)** One review round on the pilot, then every role copies
  an approved pattern.
- B. All roles in parallel immediately. Rejected: each would invent its own
  panels before a shared pattern exists.
- C. Side nav only, dashboards later. Rejected: delivers no dashboards and no
  beautify.

## How it gets built

### Step 1 — shared pieces (no visual change)

Move out of `features/technicalSupport/` (TS imports them from the new place):

| Piece | From | To |
|---|---|---|
| `useResource` + `Resource` type | `technicalSupport/shared/useResource.ts` | `lib/useResource.ts` |
| `ResourceView` | `technicalSupport/shared/ResourceView.tsx` | `components/ui/ResourceView.tsx` |
| `RowLink` | `technicalSupport/shared/RowLink.tsx` | `components/ui/RowLink.tsx` |
| `Panel` (now a private function in `TechnicalSupportDashboardPage.tsx`) | same | `components/ui/Panel.tsx` |

Their CSS rules (`row-link*`, `tech-panel*`, `tech-queue*`, `tech-badge*`,
`tech-empty`, `tech-load-error`, the dashboard grid `tech-home*`) move from
`technicalSupport.css` into a shared stylesheet under `components/ui/`, renamed
to role-neutral class names (e.g. `dash-panel`, `dash-queue`, `dash-badge`).
The rename applies across all TS pages, not just the dashboard (my call).
TS keeps its current appearance. Its tests change only import paths and
class-name selectors (`.tech-badge` etc. in 5 TS test files), never what they assert.

The side-nav shell `components/layout/AppShell` is already role-agnostic (it
takes `navLabel` + `items`) and sits inside the top-bar `components/AppShell`;
it is reused as-is. `NavIcon` gains the icons the new nav entries need.

### Step 2 — Coordinator pilot

- Nav (`features/coordinator/coordinatorNav.ts`, like `technicalSupportNav.ts`):
  Dashboard · Review queue · My events · Venue issues · Search venues.
- `CoordinatorRoutes` in `App.tsx` wraps its routes in the side-nav shell the
  way `TechnicalSupportRoutes` does.
- `CoordinatorHomePage` becomes the dashboard: a greeting header with a one-line
  summary ("3 requests to review · 1 change waiting"), then four panels, top 5
  rows each, "View all →" to the full page:

| Panel | Source (already called today) | Row links to | View all |
|---|---|---|---|
| Requests to review | `GET /event-requests/queue` → `needsReview` | `/coordinator/requests/:id` | `/coordinator/review-queue` |
| Upcoming events | `GET /events/assigned`, soonest first | `/coordinator/events/:id` | `/coordinator/my-events` |
| Change requests waiting | `GET /change-requests` (pending ones the caller may see) | `/coordinator/change-requests/:id` | `/coordinator/my-events` |
| Venue issues | `GET /coordinator/operational-issues` | per `OperationalIssuesPage` | `/coordinator/operational-issues` |

- Remove the Coordinator placeholders (`venue-search`, `confirm-event`,
  `change-review`) and `skeletonRoutes(coordinatorFeatures)`. All three are
  built elsewhere (`VenueSearchPage`, `EventDetailsPage`, `ChangeRequestReviewPage`).
- **Stop for user review of the pilot before step 3.**

### Step 3 — other roles (parallel, one agent per role, after pilot approval)

Same pattern: a `<role>Nav.ts`, routes wrapped in the shell, home page becomes a
dashboard of `Panel`s built from data that role's pages already load.

| Role | Nav | Dashboard notes |
|---|---|---|
| Coordinator Lead | Dashboard · Unassigned · Assignments · Reassignment requests · Active events | |
| Venue Staff | Dashboard · Catalogue · Booking approvals · Availability calendar | `VenueMapExplorer` becomes one panel, unchanged |
| Organiser | Dashboard · My requests · New request | today's `OrganiserHomePage` moves, unchanged (row actions, status filter, notices), to a new "My requests" page; the dashboard shows its top 5 |
| Safety Officer | Dashboard · Safety reviews | today's `SafetyOfficerHomePage` moves, unchanged (all 3 sections), to a new "Safety reviews" page; the dashboard shows the top 5 of each |
| Attendee | Dashboard · Browse events · My registrations | |

Panel contents per role are the builder's call within that role's existing
data, following the pilot.

### Step 4 — remove the placeholder system

Delete `FeatureSkeletonPage`, `SkeletonFeatureGrid`, `RoleConsoleHomePage`,
`skeleton.css`, `types/skeletonFeature.ts`, every `*Features.ts`
(`attendeeFeatures`, `coordinatorFeatures`, `coordinatorLeadFeatures`,
`organiserExtraFeatures`, `venueStaffFeatures`) and `skeletonRoutes` in
`App.tsx`. `skeleton-console*` classes used by `VenueStaffHomePage` go with
its dashboard rewrite.

### Organiser and Safety Officer list pages (approved by user)

Both home pages list everything today, so capping dashboard panels at 5 would
hide items. Each gets a full-list page instead: the current page component is
moved to the new route as-is, with its tests (story tags kept: EO15, EO07,
EO03, EO26, EC-NEW2, SO-C*). "View all" on the dashboard goes there. Old
links to the role home keep working because the home becomes the dashboard.

### Reachability check (every step)

Before deleting a route or home-page link: list every `<Route>` in `App.tsx`
and every `<Link to=…>` that reaches it, and confirm each real page stays
reachable. Each API call in the Appendix "in use" set must still be made by
some page.

## Testing

- Each new dashboard: a component test per panel — rows render from stubbed
  data, "View all" goes to the right page, loading / error + Try again / empty
  states. Use `test/apiStubs.ts`.
- Side nav per role: the entries render and the current page is highlighted
  (pattern: `components/layout/SideNav.test.tsx`).
- Existing tests that click the old home-page buttons or assert placeholder
  text are updated to the new nav/dashboard, keeping their story tags.
- Story IDs (user's decision: each console is a task on a reopened existing
  story, as TS09 hosted the TS console): shared pieces + Coordinator → EC09;
  Lead → ECL-C1; Venue Staff → VS02; Organiser → EO15; Safety Officer → SO-C1;
  Attendee → AT04. Dashboards satisfy no numbered AC of these stories, so test
  titles are `"<StoryID> (<case>): …"` unless the user adds an AC line.
- Live check: `docker compose up --build`, sign in as each role, click through
  the nav and every dashboard panel.

## Left to the builder

- Exact role-neutral CSS class names; icon shapes for new nav entries.
- Panel row details (which fields show under each title).
- Whether a dashboard needs a summary line when one panel fails to load
  (follow TS's `summarise`).

## Appendix — API use (2026-11-01 sweep)

All ~150 backend endpoints are called by app code except:
`GET /api/events/{eventId}/venue-bookings/{bookingId}/history`,
`GET /api/venue-bookings/{bookingId}/history`,
`GET /api/equipment/{equipmentId}/units/{serial}/periods`,
`GET /api/equipment-requests/processing`, `GET /api/mock`.
`POST /api/equipment/reservations` is used only by `e2e/equipment.spec.ts`.
No frontend call targets a missing endpoint. These are out of scope here; the
user will decide on them later.
