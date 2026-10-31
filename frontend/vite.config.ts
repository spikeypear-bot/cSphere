import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { configDefaults } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Proxies API calls to the Spring Boot backend (docker-compose's `backend`
    // service, or `mvnw spring-boot:run` locally) so the browser never makes a
    // cross-origin request — no CORS configuration needed on the backend.
    proxy: {
      '/api': {
        // API_PROXY_TARGET lets E2E runs point at a backend on another port.
        target: process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: false,
    // Browser E2E tests (Playwright, IS212 Week 9) run separately: npm run test:e2e.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    // Only collected by `npm run test:coverage`; plain `npm test` skips it.
    // `include` lists every source file, so files no test imports still show
    // up at 0% instead of silently missing from the report.
    coverage: {
      provider: 'v8',
      // json-summary feeds .github/scripts/coverage-summary.py in CI.
      reporter: ['text-summary', 'html', 'json-summary'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx', 'src/**/*.d.ts'],
      // DEV16: every Venue Staff/map source file must retain complete coverage.
      // Other role modules continue to use diagnostic coverage reporting.
      thresholds: {
        'src/features/venueStaff/**': { statements: 100, branches: 100, functions: 100, lines: 100 },
        'src/features/venueMap/**': { statements: 100, branches: 100, functions: 100, lines: 100 },
        'src/components/venue/**': { statements: 100, branches: 100, functions: 100, lines: 100 },
        'src/features/coordinator/CoordinatorVenueMap.tsx': { statements: 100, branches: 100, functions: 100, lines: 100 },
      },
    },
  },
})
