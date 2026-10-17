// @stories VS-C3 VS-C2
import { expect, test } from '@playwright/test'

for (const mobile of [false, true]) {
  test(`booking expiry and focus refresh preserve scroll and the open editor (${mobile ? 'mobile' : 'desktop'})`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    await page.clock.install({ time: new Date('2030-10-19T00:00:00Z') })
    await page.clock.pauseAt(new Date('2030-10-19T00:00:01Z'))
    await page.addInitScript(() => localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'fixture', refreshToken: 'fixture', userId: 'vs1', username: 'vs1',
      role: 'venue-staff', organisation: 'ConnectSphere',
    })))
    const booking = {
      bookingId: 'scroll-fixture', status: 'pending',
      venue: { venueId: 'v1', venueAddress: 'Seminar Room', venueCapacity: 200,
        supportedLayouts: ['classroom'], venueAccessibilities: ['step_free_access'], venueFacilities: ['projection'],
        operatingInformation: 'Daily', additionalInformation: null, setupMinutes: 30, turnaroundMinutes: 45 },
      event: { eventId: 'e1', eventName: 'Scroll fixture', startDatetime: '2030-10-19T10:00:00+08:00',
        endDatetime: '2030-10-19T12:00:00+08:00', expectedAttendance: 120, venueRequirements: 'Classroom seating',
        accessibilityNeeds: ['step_free_access'], equipmentRequirements: 'Projector' },
      timing: { startDatetime: '2030-10-19T10:00:00+08:00', endDatetime: '2030-10-19T12:00:00+08:00',
        setupDurationMinutes: 30, turnaroundDurationMinutes: 45, effectiveStart: '2030-10-19T09:30:00+08:00',
        effectiveEnd: '2030-10-19T12:45:00+08:00', timingNote: 'Crew access', conflicts: [], editable: true },
    }
    const hold = { holdId: 'h1', bookingId: booking.bookingId, venueId: 'v1', eventId: 'e1', eventName: 'Scroll fixture',
      reservedStart: booking.timing.effectiveStart, reservedEnd: booking.timing.effectiveEnd,
      status: 'active', serverTime: '2030-10-19T00:00:00Z', expiresAt: '2030-10-19T00:00:02Z',
      createdAt: '2030-10-18T23:00:00Z', createdBy: 'vs1', endedAt: null }
    const releaseReads: (() => void)[] = []
    let deferReads = false
    let reads = 0
    await page.route('**/api/**', async route => {
      if (!route.request().url().includes('/venue-bookings/scroll-fixture')) {
        await route.fulfill({ json: [] }); return
      }
      reads++
      if (deferReads) await new Promise<void>(resolve => { releaseReads.push(resolve) })
      await route.fulfill({ json: { ...booking, holds: [{ ...hold, status: reads === 1 ? 'active' : 'expired' }] } })
    })
    await page.goto('/venue-staff/bookings/scroll-fixture?from=booking-approvals')
    await expect(page.getByLabel('Status: tentative')).toBeVisible()
    await page.getByText('Edit booking timings', { exact: true }).click()
    const setup = page.getByLabel('Setup (minutes)')
    await setup.scrollIntoViewIfNeeded()
    const beforeExpiry = await page.evaluate(() => window.scrollY)
    expect(beforeExpiry).toBeGreaterThan(300)
    deferReads = true
    await page.clock.runFor(2100)
    await expect.poll(() => reads).toBeGreaterThan(1)
    await expect(page.getByRole('region', { name: 'Event Requirements' })).toHaveCount(1)
    expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(beforeExpiry - 150)
    // Keep all concurrent expiry reads paused, then let them complete together.
    deferReads = false
    releaseReads.forEach(resolve => resolve())
    await expect(setup).toBeEnabled()
    await expect(page.locator('.booking-timings-panel details')).toHaveAttribute('open', '')
    await setup.fill('75')
    const beforeFocus = await page.evaluate(() => window.scrollY)
    const refreshed = page.waitForResponse(response => response.url().includes('/venue-bookings/scroll-fixture'))
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await refreshed
    await expect(setup).toHaveValue('75')
    await expect(page.locator('.booking-timings-panel details')).toHaveAttribute('open', '')
    expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(beforeFocus - 150)
  })
}
