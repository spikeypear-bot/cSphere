# Browser end-to-end tests (IS212 Week 9)

A few Playwright tests for the journeys that matter most, run in a real
browser against the real backend and database. They are deliberately few:
E2E tests are slow and the most prone to flakiness, so the unit and
integration tests do the detailed checking (Week 9's test pyramid).

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

## Writing a new one

- Only for a journey a user would notice breaking (sign-in, submitting a
  request, confirming an event), not for detail a unit test can check.
- Find elements by role, label or visible text, as a user would; never CSS
  paths.
- Wait for a condition with `expect(...)`, never `waitForTimeout`.
- Create your own data with a unique name (`Date.now()`), so a test never
  depends on what an earlier run left behind.
- Never "fix" a failure by adding retries: find out why it fails.
