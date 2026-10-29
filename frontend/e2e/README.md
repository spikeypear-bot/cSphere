# Browser end-to-end tests (IS212 Week 9)

A few Playwright tests for the journeys that matter most, run in a real
browser against the real backend and database. They are deliberately few:
E2E tests are slow and the most prone to flakiness, so the unit and
integration tests do the detailed checking (Week 9's test pyramid).

`tentative-holds.spec.ts` covers VS-C3 create → calendar display → release →
re-hold → conversion, retaining one booking and the event's Planning status.
Run it alongside `golden-path.spec.ts` when changing hold/booking integration.
For optional local demo fixtures and manual expiry/notification checks, see
[the VS-C3 guide](../../docs/vsc3-manual-testing.md).

`booking-scroll.spec.ts` uses mocked booking responses and a controlled browser
clock to check that hold expiry and focus refresh preserve scroll position,
the expanded timing editor and unsaved input on desktop and mobile. It needs
the built frontend preview, but no backend or database:

```powershell
npm run test:e2e -- e2e/booking-scroll.spec.ts
```

CI runs them in the `e2e` job against the **packaged** jar and the **built**
frontend. Locally:

```powershell
# 1. Once: install the browser Playwright drives (~120 MB)
cd frontend
npx playwright install chromium

# 2. Backend with the dev profile (seeds eo1/ec1/... with password 123456).
#    docker compose up already does this on port 8080.

# 3. Build and serve the frontend (proxies /api to the backend)
npm run build
npx vite preview --port 4173
#    Backend on another port? $env:API_PROXY_TARGET='http://localhost:8081' first.

# 4. In a second terminal
npm run test:e2e
```

A failed run leaves a trace in `test-results/`; replay it step by step with
`npx playwright show-trace <path-to-trace.zip>`.

## Attendee registration history (AT04)

`attendee-badges.spec.ts` separately checks the shared pair of status badges on
browse, event details, My Registrations and confirmation details using controlled
API fixtures. It checks both light and dark modes and measures text contrast
(at least 4.5:1) for all five badge tones. Run alongside the real attendee journey:

```powershell
npm run test:e2e -- e2e/attendee-badges.spec.ts e2e/attendee-registration-status.spec.ts
```

`attendee-registration-status.spec.ts` covers AT01 registration → AT04 list and
details → AT02 latest event details, plus a direct API ownership check for a
second attendee. It creates its own unique confirmed event through the API,
using the shared fixture helper's optional registration settings; it does not
depend on the manually seeded AT04 demo batch. The test also captures desktop
and mobile details screenshots under `test-results/`.
AT03 extends the same journey to check complete on-screen confirmation, the
registration-specific notification destination, reload, and confirmation access
after signing in again. See the [AT03 contract](../../docs/registration-confirmation.md).

```powershell
npm run test:e2e -- e2e/attendee-registration-status.spec.ts
```

For status mapping, read-only guarantees, AT08 boundaries and the current local
demo data, see the [AT04 manual guide](../../docs/attendee-registration-status-manual-testing.md).

## Attendee withdrawal (AT08)

`attendee-withdrawal.spec.ts` registers through the UI, cancels the confirmation
dialog without changing state, then withdraws on mobile. It reopens the exact
receipt, checks history and released capacity, registers again while preserving
the withdrawn record, and verifies another attendee cannot open that record.
Run alongside the existing AT01–AT04 journey:

```powershell
npm run test:e2e -- e2e/attendee-withdrawal.spec.ts e2e/attendee-registration-status.spec.ts
```

The [AT08 contract](../../docs/attendee-withdrawal-api.md) describes transaction,
delivery/retry and stale-state safeguards. AT07/AT09 remain independent.

`attendee-resilience.spec.ts` checks the legacy `/attendee/register` redirect and
real response-loss recovery: each write commits on the backend, then its browser
response is dropped. The UI recovers the saved registration/withdrawal with one
POST per action and one receipt per type. Use a separate disposable browser
database from backend integration tests so saved browser fixtures do not alter
history-count assertions. See the [workflow audit](../../docs/attendee-workflow-audit.md).

## Writing a new one

- Only for a journey a user would notice breaking (sign-in, submitting a
  request, confirming an event), not for detail a unit test can check.
- Find elements by role, label or visible text, as a user would; never CSS
  paths.
- Wait for a condition with `expect(...)`, never `waitForTimeout`.
- Create your own data with a unique name (`Date.now()`), so a test never
  depends on what an earlier run left behind.
- Never "fix" a failure by adding retries: find out why it fails.
