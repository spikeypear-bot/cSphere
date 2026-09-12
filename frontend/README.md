# ConnectSphere — Frontend

The frontend app shell for ConnectSphere (DEV11): routing, shared layout,
authentication-aware navigation, and the shared API client that every
feature builds on. See `../AI_Context.md` (repo root) for full project
context and conventions; this file only covers the frontend.

## Tech stack

- [Vite](https://vite.dev/) + React 19 + TypeScript
- [React Router](https://reactrouter.com/) v7 for routing
- [Vitest](https://vitest.dev/) + [Testing Library](https://testing-library.com/) for tests

## Installation

```bash
cd frontend
npm install
```

## Starting the app

```bash
npm run dev       # dev server with hot reload, http://localhost:5173
npm run build     # type-checks (tsc -b) then produces a production build in dist/
npm run preview   # serves the dist/ build locally, to sanity-check a production build
npm run test      # runs the test suite once
npm run test:watch
npm run lint
```

## Frontend-to-backend connection

The backend (Spring Boot) runs separately — see `../backend/` and
`../docker-compose.yml` — and listens on `http://localhost:8080` by default.

- **In dev** (`npm run dev`), `vite.config.ts` proxies every request under
  `/api` to `http://localhost:8080`, so the browser only ever talks to the
  Vite dev server and no CORS configuration is needed on the backend. Start
  the backend first (`../backend/mvnw spring-boot:run`, or
  `docker compose up backend db` from the repo root), then start the
  frontend.
- **In production**, the API client (`src/lib/apiClient.ts`) requests a
  relative `/api/...` path by default, so serving the built frontend from the
  same origin as the backend (or behind a reverse proxy that forwards `/api`
  to it) needs no extra configuration. If the backend is ever deployed on a
  *different* origin, set `VITE_API_BASE_URL` — see "Environment
  configuration" below.

## Environment configuration

Copy `.env.example` to `.env.local` (already git-ignored) to override
defaults. Currently the only variable is:

| Variable              | Default | Purpose                                                                 |
| --------------------- | ------- | ------------------------------------------------------------------------ |
| `VITE_API_BASE_URL`   | `/api`  | Prefix the API client puts in front of every request path. Only needs to be set when the frontend and backend are not served from the same origin. |

Vite only exposes env vars prefixed `VITE_` to client code (its own
convention, not a ConnectSphere one) — see
[Vite's env docs](https://vite.dev/guide/env-and-mode).

## Routing

`src/App.tsx` is the single, central place routes are declared — there is no
other router configuration anywhere in the app. It's organised in two tiers:

1. A top-level `<Routes>` mapping `/`, and one `/<role>/*` prefix per role
   (`organiser`, `coordinator`, `venue-staff`, `technical-support`,
   `attendee`), to that role's route group component.
2. Each `<Role>Routes` component role-gates itself (`useRoleGate`, see
   "Authentication-aware navigation" below) and declares that role's own
   routes: an `index` route for the role's home page, any real feature
   routes, and a `skeletonRoutes(...)` spread for every not-yet-built page in
   that role's `*Features.ts` file (see "Placeholders" below).

Adding a new implemented page for an existing role means adding one
`<Route>` inside that role's `<Role>Routes` function — nothing else needs to
change to make it reachable.

### Placeholders for approved Sprint 1 stories

Every role selectable from the main page lands on a real, navigable page —
never a dead end — even for stories nobody has built yet. Each role has a
`src/features/<role>/<role>Features.ts` file listing its not-yet-built
stories as `SkeletonFeature` objects (see `src/types/skeletonFeature.ts`);
`SkeletonFeatureGrid` renders one link-card per entry on that role's home
page, and `skeletonRoutes(...)` in `App.tsx` turns each into a real route
rendering `FeatureSkeletonPage` — a placeholder that previews the page's
eventual layout and names the backlog story ID(s) and backend package it
maps to, so whoever picks up that story next has a concrete file and route
to start from.

## Shared page layout

`src/components/AppShell.tsx` wraps every route (see `App.tsx`) and provides
the header (brand + session controls) and content area common to every page.
Add a new page by giving it its own component under
`src/features/<role>/` — it renders inside `AppShell` automatically once
routed.

## Authentication-aware navigation

There is no real login yet (see `../docs/decision-log.md` D6a) — a role is
chosen with a "Login as [Role]" button on the main page
(`src/features/roleSelect/RoleSelectPage.tsx`), stored via
`SessionProvider`/`useSession` (`src/lib/session.tsx`,
`src/lib/sessionContext.ts`), and persisted to `localStorage` so a refresh
doesn't lose it.

- **Signed-out vs. signed-in:** `AppShell`'s header shows nothing extra when
  `role` is `null`; once a role is set, it shows the role's label,
  organisation (if any), and a "Switch role" control.
- **Role-appropriate navigation once role is known:** each `/<role>/*` route
  group (`App.tsx`) is gated by `useRoleGate`, which checks the current
  session's `role` against the route's expected role — visiting a route that
  doesn't match the signed-in role redirects to the role selector.
- **This is a UI convenience, not the security control.** `useRoleGate`'s own
  comment says so explicitly: hiding routes/links client-side stops nothing
  by itself — it only avoids showing a signed-in user pages that aren't
  theirs. **The backend/API is, and remains, responsible for enforcing who
  can actually perform which action** (see `AU04` in
  `../docs/product-context.md` and `../docs/decision-log.md` Q2 for whether
  server-side enforcement is already in place during this interim,
  no-real-accounts phase).

## Shared API client

`src/lib/apiClient.ts` is the one place that knows how to call the backend —
features import `apiClient.get/post/put`, never call `fetch` directly, so
request handling stays consistent app-wide. It normalises four outcomes:

| Outcome                              | Behaviour                                                                                                  |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Success                              | Resolves with the parsed JSON body (or `undefined` for a `204`).                                            |
| Validation error (e.g. `422`)        | Rejects with an `ApiClientError` whose `.missingFields` names the fields that failed validation.             |
| Unauthorised / expired session (`401`/`403`) | Rejects with an `ApiClientError` (`.isUnauthorised` is `true`) **and** dispatches a `connectsphere:unauthorised` `window` event; `SessionProvider` listens for it and clears the session, so any route gated by `useRoleGate` bounces back to the role selector — the same shared path a real "your login expired" flow will use later. |
| Unexpected failure (network error, other 4xx/5xx) | Rejects with an `ApiClientError` carrying a status code and a message safe to show the user.                 |

Base URL and environment handling is centralised there too — see
"Environment configuration" above.

## Folder structure

```
src/
  components/       Shared, cross-feature UI: AppShell (layout), the
                     skeleton-placeholder system, and components/ui/ for
                     primitives (Button, Card, form fields, status badges,
                     loading/validation indicators).
  features/<role>/   One folder per role. Each real page and its styles live
                     here; each role also has a `<role>Features.ts` listing
                     its still-unbuilt pages (see "Placeholders" above).
  lib/               Cross-cutting app code: the API client (apiClient.ts)
                     and the session/auth-state module (session.tsx,
                     sessionContext.ts).
  types/             Shared TypeScript types used across features
                     (skeletonFeature.ts, eventRequest.ts).
  assets/            Static images/icons bundled by Vite.
  test/              Test environment setup (test/setup.ts), wired in
                     vite.config.ts.
public/              Static files served as-is (favicon, etc.) — not
                     processed by Vite.
```

## Shared UI elements

`src/components/ui/` holds the building blocks reused across pages —
`Button`, `Card`, form `fields` (with built-in validation-message display),
`StatusBadge`, `StepIndicator`, `AutosaveIndicator` (a loading/saved-state
indicator), and `ChipGroup`. Reach for one of these before adding a
one-off; if a page needs something none of them cover, prefer extending an
existing primitive over hand-rolling a parallel version of it.

## Mocked vs. live data

Two different things are "mocked" right now, deliberately kept distinguishable:

- **Session/role is always mocked.** There's no real backend-verified login
  — `SessionProvider` stores whatever role/organisation was picked on the
  main page, nothing more. This is documented in `../docs/decision-log.md`
  D6a and in the "Authentication-aware navigation" section above; it isn't
  meant to be mistaken for real authentication.
- **Page data is live wherever a feature has a real backend slice, and a
  placeholder otherwise.** The Event Organiser's request list and wizard
  (`src/features/organiser/`) call the real backend through `apiClient`. Every
  other page reachable from a role's home screen that hasn't been built yet
  renders through `FeatureSkeletonPage` (see "Placeholders" above) — it is
  visually marked with a "Skeleton" badge and states plainly that it previews
  a future page rather than showing real data, so a mocked/placeholder page
  is never mistaken for a working one.

## Viewport support

Layouts use CSS grid/flexbox with wrapping and `auto-fill`/`minmax` sizing
(see `src/components/AppShell.css`, `RoleSelectPage.css`,
`skeleton.css`) so pages reflow down to mobile widths without needing a
separate mobile layout; a few components add explicit breakpoints
(`AppShell.css`, `EventRequestWizardPage.css`) where the flex/grid behaviour
alone isn't enough.

## Testing

Tests live next to the code they cover (`*.test.ts`/`*.test.tsx`) and run
under Vitest with jsdom (`vite.config.ts`'s `test` block) and Testing
Library. `npm run test` runs the whole suite once; `npm run test:watch` reruns
on change. Current coverage includes: routing/role-gating (`App.test.tsx`),
the API client's four response outcomes including the unauthorised/session
handling above (`src/lib/apiClient.test.ts`), the session store reacting to
an unauthorised event (`src/lib/session.test.tsx`), and feature-level tests
for the role selector and the event request wizard.
