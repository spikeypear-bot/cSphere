// @stories AT01 AT02 AT03 AT04
import { expect, test } from '@playwright/test'

for (const theme of ['light', 'dark'] as const) {
  test(`AT02 AT03 AT04 (cross-cutting): exactly two consistent badges per attendee section with readable ${theme} colours`, async ({ page }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme })
    await page.addInitScript(() => localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'badge-test', refreshToken: 'badge-test', userId: 'att1', username: 'att1', role: 'attendee', organisation: 'Acme',
    })))
    const record = { eventId: 'event-1', eventName: 'Badge demonstration', registrationId: 'reg-1',
      reference: 'CS-BADGES', registeredAt: '2026-10-01T00:00:00Z', registrationStatus: 'confirmed',
      startDatetime: '2027-03-01T01:00:00Z', endDatetime: '2027-03-01T09:00:00Z',
      eventStatus: 'confirmed', eventDetailsAvailable: true, withdrawalAvailable: true, withdrawalReason: 'Before event start.' }
    const event = { ...record, status: 'confirmed', registrationReference: record.reference,
      registrationRequired: true, registrationOpensAt: '2020-01-01T00:00:00Z',
      registrationClosesAt: record.startDatetime, registrationCapacity: 10, availablePlaces: 0,
      updatedAt: null, venues: [], accessibilityInformation: [], attendeeInstructions: null }
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname
      let data: unknown = event
      if (path.endsWith('/unread-count')) data = { count: 0 }
      else if (path === '/api/notifications') data = []
      else if (path === '/api/attendee/registrations') data = [record]
      else if (path === '/api/attendee/registrations/reg-1') data = record
      else if (path === '/api/attendee/events') data = [event]
      return route.fulfill({ json: data })
    })
    for (const path of ['/attendee/browse', '/attendee/events/event-1', '/attendee/my-registrations', '/attendee/my-registrations/reg-1']) {
      await page.goto(path)
      await expect(page.getByText('Registration Confirmed', { exact: true })).toBeVisible()
      const group = page.getByLabel('Event and registration statuses')
      await expect(group).toHaveCount(1)
      await expect(page.locator('[data-attendee-badge]')).toHaveCount(2)
      await expect(group.getByText('Confirmed', { exact: true })).toBeVisible()
      const outline = await group.locator('[data-attendee-badge="registration"]').evaluate(badge => {
        const styles = getComputedStyle(badge)
        return { background: styles.backgroundColor, border: styles.borderTopColor,
          colour: styles.color, width: styles.borderTopWidth, style: styles.borderTopStyle }
      })
      expect(outline.background).toMatch(/(?:,\s*|\/\s*)0\.05\)$/)
      expect(outline.border).toBe(outline.colour)
      expect(outline.width).toBe('1px')
      expect(outline.style).toBe('solid')
    }
    // Exercise every palette tone using real styles and rendered foreground/background colours.
    await page.route('**/api/attendee/events', route => route.fulfill({ json: [
      { ...event, eventId: 'open', eventName: 'Open', registrationStatus: null, registrationReference: null, availablePlaces: 5 },
      { ...event, eventId: 'full', eventName: 'Full', registrationStatus: null, registrationReference: null },
      { ...event, eventId: 'future', eventName: 'Future', registrationStatus: null, registrationReference: null, registrationOpensAt: '2027-02-01T00:00:00Z' },
      { ...event, eventId: 'paused', eventName: 'Paused', status: 'reconfirming', registrationStatus: null, registrationReference: null },
      { ...event, eventId: 'past', eventName: 'Past', status: 'completed', registrationStatus: null, registrationReference: null },
    ] }))
    await page.goto('/attendee/browse')
    await expect(page.locator('[data-attendee-badge]')).toHaveCount(10)
    const ratios = await page.locator('[data-attendee-badge]').evaluateAll(badges => {
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = 1
      const context = canvas.getContext('2d')!
      const luminance = (colour: ArrayLike<number>) => {
        const rgb = Array.from(colour).slice(0, 3).map(value => {
          const channel = value / 255
          return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
        })
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
      }
      return badges.map(badge => {
        const styles = getComputedStyle(badge)
        context.clearRect(0, 0, 1, 1)
        context.fillStyle = styles.color
        context.fillRect(0, 0, 1, 1)
        const foreground = luminance(context.getImageData(0, 0, 1, 1).data)
        // Composite the faint fill over its actual containing surfaces.
        context.fillStyle = 'white'
        context.fillRect(0, 0, 1, 1)
        const surfaces: Element[] = []
        for (let surface: Element | null = badge; surface; surface = surface.parentElement) surfaces.push(surface)
        for (const surface of surfaces.reverse()) {
          context.fillStyle = getComputedStyle(surface).backgroundColor
          context.fillRect(0, 0, 1, 1)
        }
        const background = luminance(context.getImageData(0, 0, 1, 1).data)
        return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
      })
    })
    for (const ratio of ratios) expect(ratio).toBeGreaterThanOrEqual(4.5)
    await page.screenshot({ path: testInfo.outputPath(`attendee-badges-${theme}.png`), fullPage: true })
  })
}
