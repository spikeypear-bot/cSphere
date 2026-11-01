# EC09 Coordinator Console (pilot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Event Coordinator the Technical Support console treatment (side nav + "what needs your attention" dashboard) built from components lifted out of Technical Support, and remove the Coordinator's placeholder pages.

**Architecture:** Move TS's `useResource`, `ResourceView`, `RowLink` and the private `Panel` into `lib/` and `components/ui/`, with their CSS in a role-neutral `components/ui/dashboard.css`. TS imports them from there and looks unchanged. `CoordinatorRoutes` is wrapped in the existing role-agnostic side-nav shell (`components/layout/AppShell`, imported in `App.tsx` as `ConsoleShell`), and `CoordinatorHomePage` becomes a four-panel dashboard reading endpoints the Coordinator pages already call.

**Tech Stack:** React 19, react-router-dom 7, TypeScript ~6, Vitest + React Testing Library, plain CSS.

**Spec:** `docs/superpowers/specs/2026-11-01-role-consoles-design.md` (Steps 1–2 and the scope note; Steps 3–4 are later plans).

## Global Constraints

- No backend change. No new dependency.
- Statuses: show the server's `journeyStatus` via `JourneyStatusBadge`; never add a status label map (AI_Context D22).
- Times in Singapore time: use existing helpers (`formatEventTimeRange` in `features/venueStaff/formatEventDateTime.ts`, `describeWhen` in `features/technicalSupport/shared/format.ts`).
- Every new test file: line 1 `// @stories <IDs>`; top-level `describe` title starts with the story ID; test titles `"EC09 (<case>): …"` with `<case>` one of happy / negative / boundary / conflict / failure / cross-cutting. **No AC numbers** (none cover a dashboard). Edited test files keep all existing story tags.
- Technical Support must look and behave exactly as before; its tests change only import paths and class-name selectors.
- Work on branch `feat/ec09-coordinator-console`, not `main`.
- Verification commands run from `frontend/`: `npx vitest run`, `npm run lint`, `npm run build`; traceability from repo root: `python3 .github/scripts/traceability.py --check`.

## Review Focus

- **One panel fails, others load:** the failed panel shows its error with "Try again" and the rest render; the summary line counts only what loaded. → test in Task 3.
- **Nothing to do:** every panel shows its empty message (never a blank box), summary still renders. → test in Task 3.
- **More than 5 items:** exactly 5 rows show; "View all" reaches the rest. → test in Task 3.
- **Bookmarked old placeholder URL** (`/coordinator/confirm-event`, `/coordinator/change-review`): lands on the dashboard, not a blank page inside the shell. → test in Task 4.
- **Venue issue with no overlapping events:** row still renders with its venue and description. → test in Task 3.

---

### Task 1: Lift the TS dashboard pieces into shared components

**Files:**
- Create: `frontend/src/lib/useResource.ts` (moved from `features/technicalSupport/shared/useResource.ts`, content unchanged)
- Create: `frontend/src/components/ui/ResourceView.tsx` (moved from `features/technicalSupport/shared/ResourceView.tsx`)
- Create: `frontend/src/components/ui/RowLink.tsx` (moved from `features/technicalSupport/shared/RowLink.tsx`)
- Create: `frontend/src/components/ui/Panel.tsx` (extracted from the private `Panel` in `features/technicalSupport/dashboard/TechnicalSupportDashboardPage.tsx:60-80`)
- Create: `frontend/src/components/ui/dashboard.css`
- Delete: the three `features/technicalSupport/shared/` originals above
- Modify: every importer of the moved files (find with `grep -rln "shared/useResource\|shared/ResourceView\|shared/RowLink" frontend/src`)
- Modify: `frontend/src/features/technicalSupport/technicalSupport.css` (rules moved out), and every `.tsx` using the renamed classes (37 occurrences: `grep -rn "tech-badge\|tech-panel\|tech-queue\|tech-home\|tech-empty\|tech-load-error" frontend/src`)
- Test: existing `TechnicalSupportDashboard.test.tsx`, `EquipmentRequests.test.tsx`, `TechnicalSupportPages.test.tsx`, `FaultyEquipment.test.tsx` (selectors only)

**Interfaces:**
- Produces:
  - `useResource<T>(load: () => Promise<T>, key: string): Resource<T>` and `interface Resource<T> { data: T | null; error: string | null; loading: boolean; retry: () => void }` from `lib/useResource.ts`
  - `ResourceView<T>(props: { resource: Resource<T>; loadingText: string; errorText: string; emptyText: string; emptyClassName?: string; isEmpty: (data: T) => boolean; children: (data: T) => ReactNode })` from `components/ui/ResourceView.tsx`
  - `RowLink(props: { to: string; title: ReactNode; titleAs?: 'h2' | 'h3' | 'span'; media?: ReactNode; badges?: ReactNode; aside?: ReactNode; children?: ReactNode; className?: string })` from `components/ui/RowLink.tsx`
  - `Panel(props: { title: string; action: string; actionName: string; to: string; className?: string; children: ReactNode })` from `components/ui/Panel.tsx` — renders `<section class="card dash-panel …" aria-labelledby>` with `<h2>` and the ghost "action →" link, exactly as TS's private one did.
  - CSS classes in `dashboard.css`: `row-link*` (names unchanged), `dash-grid` / `dash-grid__wide` (were `tech-home` / `tech-home__wide`), `dash-panel*` (were `tech-panel*`), `dash-queue`, `dash-queue__row`, `dash-queue__row--compact`, `dash-queue__meta`, `dash-queue__when` (were `tech-queue*`), `dash-badge`, `dash-badge--danger|--success|--warning|--neutral` (were `tech-badge*`), `dash-empty`, `dash-load-error`.

- [ ] **Step 1: Create the branch**

```bash
git switch -c feat/ec09-coordinator-console
git add docs/superpowers/specs/2026-11-01-role-consoles-design.md docs/superpowers/plans/2026-11-01-ec09-coordinator-console.md
git commit -m "docs(EC09): role consoles design and coordinator pilot plan"
```

- [ ] **Step 2: Record the TS baseline**

Run: `cd frontend && npx vitest run src/features/technicalSupport src/components/layout`
Expected: all PASS. Note the count; Step 6 must match it.

- [ ] **Step 3: Move the files and extract `Panel`**

`git mv` the three shared files to their new homes, fix their relative imports, extract `Panel` verbatim into `components/ui/Panel.tsx` (classes renamed per Interfaces), and point every importer at the new paths. `ResourceView` and `RowLink` import `./dashboard.css` instead of `../technicalSupport.css`.

- [ ] **Step 4: Move and rename the CSS**

Cut the generic rules listed in Interfaces from `technicalSupport.css` into `components/ui/dashboard.css` with the new names. TS-only rules stay in `technicalSupport.css` (`tech-today*`, `tech-left*`, `tech-nowrap`, and the `.equipment-damage__photo` sizing, re-targeted to `.dash-queue__row .equipment-damage__photo` / `.dash-queue__row--compact .equipment-damage__photo`). Rename the classes in every TS `.tsx` and in the 4 TS test files' selectors.

- [ ] **Step 5: Check nothing still uses the old names**

Run: `grep -rn "tech-badge\|tech-panel\|tech-queue\|tech-home\|tech-empty\|tech-load-error\|shared/useResource\|shared/ResourceView\|shared/RowLink" frontend/src`
Expected: no output.

- [ ] **Step 6: Run TS tests, lint, build**

Run: `cd frontend && npx vitest run src/features/technicalSupport src/components/layout && npm run lint && npm run build`
Expected: same PASS count as Step 2; lint and build clean.

- [ ] **Step 7: Live look at TS**

Run the app (`docker compose up --build` from repo root), sign in as `ts1`, open the dashboard, requests, faulty and inventory pages. Expected: identical to before (compare against `main` if unsure).

- [ ] **Step 8: Commit**

```bash
git add -A frontend/src
git commit -m "refactor(EC09): share the TS dashboard pieces (Panel, RowLink, ResourceView, useResource)"
```

---

### Task 2: Coordinator side navigation

**Files:**
- Create: `frontend/src/features/coordinator/coordinatorNav.ts`
- Modify: `frontend/src/components/layout/NavIcon.tsx` (add `calendar` and `search` shapes, same 20px line style)
- Modify: `frontend/src/App.tsx` (`CoordinatorRoutes`, around line 110–131)
- Test: `frontend/src/features/coordinator/CoordinatorNav.test.tsx`

**Interfaces:**
- Consumes: `NavItem` from `components/layout/navTypes.ts`; `AppShell` from `components/layout/AppShell.tsx` (already imported in `App.tsx` as `ConsoleShell`).
- Produces: `export const coordinatorNav: NavItem[]` =
  `Dashboard → /coordinator (dashboard)`, `Review queue → /coordinator/review-queue (requests)`, `My events → /coordinator/my-events (calendar)`, `Venue issues → /coordinator/operational-issues (faulty)`, `Search venues → /coordinator/venue-search (search)`.

- [ ] **Step 1: Write the failing test** (`// @stories EC09`; pattern: `components/layout/SideNav.test.tsx`, render via `App` with `seedSession('coordinator')` like `App.test.tsx`)

```tsx
describe('EC09 coordinator console navigation', () => {
  it('EC09 (happy): the side navigation lists the coordinator pages in order', async () => {
    // render /coordinator
    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator' })
    expect(within(nav).getAllByRole('link').map((l) => [l.textContent, l.getAttribute('href')])).toEqual([
      ['Dashboard', '/coordinator'], ['Review queue', '/coordinator/review-queue'], ['My events', '/coordinator/my-events'],
      ['Venue issues', '/coordinator/operational-issues'], ['Search venues', '/coordinator/venue-search'],
    ])
  })
  it('EC09 (happy): the current page is highlighted', async () => {
    // render /coordinator/my-events
    expect(within(nav).getByRole('link', { name: 'My events' })).toHaveAttribute('aria-current', 'page')
  })
})
```

(Use the nav's real accessible name and current-page attribute from `SideNav.tsx`; adjust the strings above if they differ, keep the assertions.)

- [ ] **Step 2: Run it, expect FAIL** — `npx vitest run src/features/coordinator/CoordinatorNav.test.tsx` → no navigation found.

- [ ] **Step 3: Implement** `coordinatorNav.ts`, the two icons, and wrap `CoordinatorRoutes`' `<Routes>` in `<ConsoleShell navLabel="Event Coordinator" items={coordinatorNav}>` as `TechnicalSupportRoutes` does. Add `<Route path="*" element={<Navigate to="/coordinator" replace />} />` as the last coordinator route.

- [ ] **Step 4: Run it, expect PASS**; then `npx vitest run src/App.test.tsx src/features/coordinator` — fix only failures caused by the shell (e.g. duplicated link names now present in the nav: use `within(main)` scoping), never weaken an assertion.

- [ ] **Step 5: Commit** — `git commit -am "feat(EC09): coordinator side navigation"` (plus `git add` the new files).

---

### Task 3: Coordinator dashboard

**Files:**
- Modify (rewrite): `frontend/src/features/coordinator/CoordinatorHomePage.tsx`
- Create: `frontend/src/features/coordinator/CoordinatorDashboard.test.tsx`
- Delete: `frontend/src/features/coordinator/coordinatorFeatures.ts`; remove `skeletonRoutes(coordinatorFeatures)` and its import from `App.tsx`
- Modify: `frontend/src/App.test.tsx` (lines ~40–53, 156, 179), `frontend/src/features/coordinatorLead/ActiveEventsPage.test.tsx` (line ~83) — heading regex and the EC04 skeleton assertion
- Modify: `frontend/src/features/coordinator/AssignedEventsPage.tsx:31` eyebrow text "Event Coordinator console" → "Dashboard" (link stays `/coordinator`)

**Interfaces:**
- Consumes: `useResource`, `ResourceView`, `RowLink`, `Panel`, dashboard classes (Task 1); `apiClient.get` from `lib/apiClient`; `JourneyStatusBadge` from `components/ui/JourneyStatus`; types `ReviewQueue` (`features/coordinator/ReviewQueuePage.tsx`), `EventDto` (`types/event`), `ChangeRequestView` (`types/changeRequest`), `CoordinatorVenueOperationalIssueDto` (`types/venueOperationalIssue`); `greetingFor`, `countOf` from `features/technicalSupport/shared/format.ts`.
- Produces: `export function CoordinatorHomePage()` (same export name, so `App.tsx` routing is untouched).

Page shape (mirror `TechnicalSupportDashboardPage`): `PageHeader` title `"${greeting}, ${username}"`, description = summary line `"N requests to review · N changes waiting"` built from whichever loaded (same fallbacks as TS `summarise`), then `<div className="dash-grid">` with four `Panel`s, each `slice(0, 5)`:

| Panel title | Load | Empty text | Row `to` | Panel `to` / action |
|---|---|---|---|---|
| Requests to review (`dash-grid__wide`) | `GET /event-requests/queue` → `.needsReview` | "No requests waiting for your review." | `/coordinator/requests/:requestId` | `/coordinator/review-queue`, "View all" |
| Upcoming events | `GET /events/assigned` (server order = soonest first) | "No events are assigned to you right now." | `/coordinator/events/:eventId`, badge `JourneyStatusBadge` | `/coordinator/my-events`, "View all" |
| Change requests waiting | `GET /change-requests` | "No change requests waiting." | `/coordinator/change-requests/:changeRequestId` | `/coordinator/my-events`, "View events" |
| Venue issues | `GET /coordinator/operational-issues` | "No venue issues affect your events." | `/coordinator/operational-issues` | `/coordinator/operational-issues`, "View all" |

Row details are the builder's call (e.g. organisation + time range for requests; venue address + description for issues).

- [ ] **Step 1: Write the failing tests** (`// @stories EC09 EC02 EC-NEW3`; `stubApi` + `seedSession('coordinator')` + `MemoryRouter`/`SessionProvider` as in `TechnicalSupportDashboard.test.tsx`; `const panel = (t) => screen.getByRole('region', { name: t })`; `const findPanel = (t) => screen.findByRole('region', { name: t })`)

```tsx
describe('EC09 coordinator dashboard', () => {
  it('EC09 (happy): each panel lists its items and links to them', async () => {
    // queue: 1 needsReview "Gala"; assigned: 1 event "Expo"; changes: 1 "Change to Expo"; issues: 1 at "1 Main St"
    expect(within(await findPanel('Requests to review')).getByRole('link', { name: 'Gala' })).toHaveAttribute('href', '/coordinator/requests/req-1')
    expect(within(panel('Upcoming events')).getByRole('link', { name: 'Expo' })).toHaveAttribute('href', '/coordinator/events/event-1')
    expect(within(panel('Change requests waiting')).getByRole('link', { name: /Expo/ })).toHaveAttribute('href', '/coordinator/change-requests/cr-1')
    expect(within(panel('Venue issues')).getByText(/1 Main St/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ec1$/)
    expect(screen.getByText('1 request to review · 1 change waiting')).toBeInTheDocument()
  })
  it('EC09 (boundary): shows at most 5 rows per panel and a View all link', async () => {
    // queue.needsReview: 7 requests
    expect(within(await findPanel('Requests to review')).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all requests to review' })).toHaveAttribute('href', '/coordinator/review-queue')
  })
  it('EC09 (boundary): every panel says so when there is nothing to do', async () => {
    // all four endpoints return empty
    expect(await screen.findByText('No requests waiting for your review.')).toBeInTheDocument()
    expect(screen.getByText('No events are assigned to you right now.')).toBeInTheDocument()
    expect(screen.getByText('No change requests waiting.')).toBeInTheDocument()
    expect(screen.getByText('No venue issues affect your events.')).toBeInTheDocument()
  })
  it('EC09 (failure): one panel failing shows Try again and the others still load', async () => {
    // GET /api/events/assigned → 500; others 200 with 1 item
    expect(within(await findPanel('Upcoming events')).getByRole('alert')).toBeInTheDocument()
    expect(within(panel('Upcoming events')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(within(panel('Requests to review')).getByRole('link', { name: 'Gala' })).toBeInTheDocument()
  })
  it('EC09 (boundary): a venue issue overlapping no events still shows', async () => {
    // issue with overlappingEvents: []
    expect(within(await findPanel('Venue issues')).getByText(/1 Main St/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run, expect FAIL** — `npx vitest run src/features/coordinator/CoordinatorDashboard.test.tsx`.

- [ ] **Step 3: Implement** the dashboard per the table; delete `coordinatorFeatures.ts` and its `skeletonRoutes` line.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Update the tests that asserted the old home**
  - `App.test.tsx` "opens the console belonging to the signed-in account": heading `/^Good \w+, ec1$/`; keep the review-queue link check but expect the nav link `Review queue` → `/coordinator/review-queue`; delete the `EC04` skeleton assertion (the placeholder is gone by design).
  - `App.test.tsx` EC09 "My events one click away": link is now the nav entry `My events`; rest unchanged.
  - Every `/Event Coordinator console/i` "own console" regex (`App.test.tsx` ~156, ~179; `ActiveEventsPage.test.tsx` ~83) → `/^Good \w+, ec1$/`. These tests stub `fetch`; if a dashboard call now hits an unexpected-route stub, add `GET /api/event-requests/queue`, `/api/events/assigned`, `/api/change-requests`, `/api/coordinator/operational-issues` returning empty bodies to that test's stubs. Keep each file's `@stories` line as-is.

- [ ] **Step 6: Full frontend check**

Run: `cd frontend && npx vitest run && npm run lint && npm run build && cd .. && python3 .github/scripts/traceability.py --check`
Expected: all PASS / clean.

- [ ] **Step 7: Commit** — `feat(EC09): coordinator dashboard replaces the placeholder home`

---

### Task 4: Old URLs, scope note, live check

**Files:**
- Modify: `frontend/src/features/coordinator/CoordinatorNav.test.tsx` (add test)
- Modify: `AI_Context.md:95`
- Check: `frontend/e2e/*.spec.ts` for "Event Coordinator console" (none found at planning time; re-grep)

- [ ] **Step 1: Test the old placeholder URLs**

```tsx
it.each(['/coordinator/confirm-event', '/coordinator/change-review'])(
  'EC09 (negative): the retired placeholder %s lands on the dashboard', async (path) => {
    // render path
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ec1$/)
  })
```

Run: `npx vitest run src/features/coordinator/CoordinatorNav.test.tsx` → PASS (the catch-all route from Task 2 provides this; if it fails, fix the route, not the test).

- [ ] **Step 2: Amend the scope note**

In `AI_Context.md:95`, change `generic dashboards/role-based overview screens (except the SO-C1–SO-C4 console described above)` to `generic dashboards/role-based overview screens (except the SO-C1–SO-C4 console described above, and each role's console — side navigation plus a dashboard of that role's existing work — delivered as a task on that role's existing story, as TS09 did for Technical Support and EC09 for the Event Coordinator; backlog VS19/TS10/AT10 stay out of scope)`.

- [ ] **Step 3: Live check (D10)**

`docker compose up --build`; sign in as `ec1`: nav highlights correctly, each panel loads real data, every row and "View all" opens the right page, collapse/expand and narrow-window drawer work; `/coordinator/confirm-event` lands on the dashboard. Sign in as `ts1`: TS unchanged.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/coordinator/CoordinatorNav.test.tsx AI_Context.md
git commit -m "docs(EC09): role consoles in scope per role story; retired coordinator URLs redirect"
```

- [ ] **Step 5: Stop** — the pilot goes to the user for review before Step 3 of the spec (other roles).
