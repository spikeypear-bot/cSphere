import { defineConfig, devices } from '@playwright/test'

/**
 * IS212 Week 9: browser end-to-end tests for the few journeys that matter
 * most. They drive the built app (vite preview, which proxies /api to the
 * backend) against a real backend and database, so they need both running:
 * see e2e/README.md. CI starts them in the `e2e` job.
 */
// One start day for the whole run (see uniqueDay in e2e/support.ts). Set here,
// in the runner, so every worker inherits the same value.
process.env.E2E_FIRST_DAY ??= String(Date.now() % 700)

export default defineConfig({
  testDir: './e2e',
  // A real browser and a real backend are slower than unit tests; fail fast
  // rather than hang, and never retry a failure into a pass (Week 9: retrying
  // until green hides flakiness instead of fixing it).
  timeout: 30_000,
  retries: 0,
  // Spec files run side by side; the tests inside one file still run in order.
  // Every test makes its own uniquely named data, so they do not collide.
  workers: 3,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4173',
    // On failure, keep a step-by-step trace to replay in the trace viewer.
    trace: 'retain-on-failure',
    // ConnectSphere's users are in Singapore. Pin the browser's timezone so a
    // run behaves the same on every machine: CI runners are UTC, and a
    // datetime-local field the app reads as browser-local time would
    // otherwise mean a different moment there (Week 9: environment-dependent
    // tests are flaky tests).
    timezoneId: 'Asia/Singapore',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
