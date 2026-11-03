// @stories EO01 EC07 TS01 TS11 SO-C3 ECL-C1 VS16
import { expect, test, type Page } from '@playwright/test'

const event = { eventId: 'event', eventName: 'Community gathering', purpose: 'Meet the community', description: null,
  startDatetime: '2030-03-10T01:00:00Z', endDatetime: '2030-03-10T04:00:00Z', expectedAttendance: 150,
  venueRequirements: 'Accessible hall', equipmentRequirements: 'Equipment items:\n2 × Audio mixer\n\nAdditional notes:\nBring cables',
  equipmentItems: [{ equipmentId: 'mixer', quantity: 2 }], accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false,
  status: 'pending', coordinatorName: 'ec1', coordinatorEmail: null }
const lines = [{ equipmentId: 'mixer', equipmentName: 'Audio mixer', quantity: 2 }]
const request = { requestId: 'request', eventId: 'event', eventName: event.eventName, status: 'processing',
  eventStart: event.startDatetime, eventEnd: event.endDatetime, technicalRequirement: event.equipmentRequirements, items: lines }
const venue = { venueId: 'venue', venueAddress: 'Singapore community hall', venueCapacity: 200, supportedLayouts: ['theatre'],
  venueFacilities: [], venueAccessibilities: [], operatingInformation: 'Daily', additionalInformation: null }
const review = { reviewId: 'review', eventId: 'event', eventName: event.eventName, eventStart: event.startDatetime, eventEnd: event.endDatetime,
  coordinatorName: 'ec1', changedAt: '2026-11-01T01:00:00Z', needsReview: true, requirementsChanged: true,
  outcome: null, outcomeNote: null, reviewedByName: null, reviewedAt: null, affectedCount: 0, affectedAtChange: [], reservations: [],
  previous: { start: event.startDatetime, end: event.endDatetime, equipmentRequirements: 'One mixer' },
  updated: { start: event.startDatetime, end: event.endDatetime, equipmentRequirements: 'Two mixers' } }

async function seed(page: Page, role: string) {
  await page.addInitScript(role => localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'fixture', refreshToken: 'fixture',
    userId: 'fixture', username: 'fixture', role, organisation: 'ConnectSphere' })), role)
  let readiness = { eventId: 'event', approvalStatus: 'not_reviewed', approvalNote: null, reviewedBy: null, reviewedAt: null,
    completedItems: 0, totalItems: 1, items: [{ itemKey: 'venue', label: 'Venue ready', checked: false, note: '',
      checkedBy: null, checkedAt: null, updatedBy: null, updatedAt: null }] }
  let draftSaved: Record<string, unknown> = {}
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let json: unknown = []
    if (path === '/api/events/event') json = event
    if (path === '/api/equipment/catalogue') json = [...lines.map(item => ({ ...item, totalQuantity: 20, serialised: false })),
      { equipmentId: 'mic', equipmentName: 'Microphone', totalQuantity: 20, serialised: false }]
    if ((path === '/api/event-requests' && route.request().method() === 'POST') || path === '/api/event-requests/draft') {
      draftSaved = route.request().postDataJSON() ?? draftSaved
      json = { ...event, ...draftSaved, requestId: 'draft', requestType: 'C', status: 'draft', organisation: 'ConnectSphere' }
    }
    if (path === '/api/event-requests/unassigned/r1') json = { request: { ...event, eventId: null, requestId: 'r1', status: 'pending',
      createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', organisation: 'Acme' }, missingFields: [], scheduleValid: true, timeline: [] }
    if (path.endsWith('/coordinators')) json = [{ userId: 'ec1', username: 'ec1' }]
    if (path === '/api/equipment-change-reviews') json = [review]
    if (path === '/api/equipment-change-reviews/review') json = review
    if (path === '/api/equipment-requests') json = [request]
    if (path === '/api/equipment-requests/request/lines') json = lines
    if (path.includes('/availability')) json = [{ ...lines[0], serialised: false, totalQuantity: 20, availableQuantity: 20 }]
    if (path === '/api/safety/events/event') json = { ...event, status: 'awaiting_safety_review' }
    if (path === '/api/events/event/equipment-requests') json = role === 'safety-officer' ? [{ ...request, lines }] : []
    if (path === '/api/safety/events/event/readiness/items/venue') {
      const saved = route.request().postDataJSON() as { checked: boolean; note: string }
      readiness = { ...readiness, items: [{ ...readiness.items[0], ...saved }] }
      json = readiness
    }
    if (path === '/api/safety/events/event/readiness') json = readiness
    if (path === '/api/venue-bookings/booking') json = { bookingId: 'booking', status: 'pending', venue, event,
      relatedBookings: [{ bookingId: '7aaa6eb7-015f-4d03-8795-e7b243630bae', venueId: 'other', status: 'pending',
        venueAddress: 'Singapore Chinese Cultural Centre – Multi-Purpose Hall, Level 7, 1 Straits Boulevard, Singapore 018906',
        startDatetime: event.startDatetime, endDatetime: event.endDatetime, effectiveStart: event.startDatetime, effectiveEnd: event.endDatetime }] }
    if (path.endsWith('/assessment')) json = { bookingId: 'booking', status: 'pending', checks: [], history: [] }
    if (path.endsWith('/unread-count')) json = { count: 0 }
    await route.fulfill({ json })
  })
  return () => draftSaved
}

for (const width of [1440, 390]) {
  test(`EO01 (happy): new requests retain selected IDs and stepper quantities at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); const saved = await seed(page, 'organiser')
    await page.goto('/organiser/requests/new')
    await page.getByLabel('Event name').fill('Community gathering')
    await page.getByLabel('Purpose').fill('Meet the community')
    await page.getByRole('button', { name: 'Next', exact: true }).click()
    await page.getByLabel('Start date & time').fill('2030-03-10T09:00')
    await page.getByLabel('End date & time').fill('2030-03-10T12:00')
    await page.getByLabel('Expected attendance').fill('150')
    await page.getByRole('button', { name: 'Next', exact: true }).click()
    await page.getByLabel('Venue requirements').fill('Accessible hall')
    await page.getByRole('button', { name: 'No accessibility requirements needed' }).click()
    await page.getByRole('button', { name: 'Next', exact: true }).click()
    await page.getByLabel('Audio mixer', { exact: true }).check()
    await page.getByRole('button', { name: 'Increase Audio mixer quantity' }).click()
    await page.getByLabel('Additional equipment notes').fill('Bring cables')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('eo-equipment.png'), fullPage: true })
    await page.getByRole('button', { name: 'Next', exact: true }).click()
    expect(saved().equipmentItems).toEqual([{ equipmentId: 'mixer', quantity: 2 }])
    expect(saved().equipmentRequirements).toContain('Bring cables')
  })

  test(`TS11 (happy): review list and detail use venue-panel spacing at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'technical-support')
    await page.goto('/technical-support/change-reviews')
    const card = page.getByRole('link', { name: event.eventName })
    await expect(card).toBeVisible()
    await expect(card.getByText('Needs review', { exact: true })).toHaveClass(/dash-badge--warning/)
    await expect(card).toHaveCSS('padding-left', width === 390 ? '16px' : '24px')
    await page.screenshot({ path: info.outputPath('reviews.png'), fullPage: true })
    await card.click(); await expect(page.getByRole('heading', { name: 'What changed' })).toBeVisible()
    await expect(page.getByRole('table')).toContainText('Two mixers')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('review-detail.png'), fullPage: true })
  })

  test(`EC07 (happy): requested-only equipment expands for editing at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'coordinator')
    await page.goto('/coordinator/events/event/equipment-request')
    await expect(page.getByRole('spinbutton', { name: 'Audio mixer quantity', exact: true })).toHaveValue('2')
    await expect(page.getByText('Microphone', { exact: true })).toHaveCount(0)
    await expect(page.getByLabel('Technical requirements or notes')).toHaveValue('Bring cables')
    await page.screenshot({ path: info.outputPath('ec-selected.png'), fullPage: true })
    await page.getByRole('button', { name: 'Edit equipment' }).click()
    await page.getByLabel('Microphone', { exact: true }).check()
    await page.getByRole('button', { name: 'Done', exact: true }).click()
    await expect(page.getByRole('spinbutton', { name: 'Microphone quantity', exact: true })).toHaveValue('1')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('ec-edited.png'), fullPage: true })
  })

  test(`TS01 (happy): technical note separates read-only equipment rows at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'technical-support')
    await page.goto('/technical-support/requests/request')
    const equipment = page.getByRole('list', { name: 'Requested equipment' })
    await expect(equipment).toContainText('Audio mixer')
    await expect(equipment.getByRole('button')).toHaveCount(0)
    expect(await equipment.locator('.equipment-row').first().evaluate(el =>
      el.getBoundingClientRect().right - el.querySelector('.equipment-row__quantity')!.getBoundingClientRect().right)).toBe(12)
    await expect(page.locator('.request-head__note')).toContainText('Bring cables')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('ts-equipment.png'), fullPage: true })
  })

  test(`SO-C3 (happy): matching read-only equipment and textarea-only notes at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'safety-officer')
    await page.goto('/safety-officer/events/event')
    await expect(page.getByRole('list', { name: 'Requested equipment' })).toContainText('Audio mixer')
    expect(await page.locator('.equipment-selection-summary .equipment-row').first().evaluate(el =>
      el.getBoundingClientRect().right - el.querySelector('.equipment-row__quantity')!.getBoundingClientRect().right)).toBe(12)
    await page.getByRole('button', { name: 'Show notes for Venue ready' }).click()
    const notes = page.getByRole('textbox', { name: 'Notes (optional) for Venue ready' })
    await expect(notes.locator('..').locator(':scope > *')).toHaveCount(1)
    await notes.fill('Entrance checked'); await notes.press('Tab')
    await expect(notes).toHaveValue('Entrance checked')
    expect(await notes.evaluate(el => getComputedStyle(el).backgroundColor))
      .toBe(await page.locator('#safety-review-note').evaluate(el => getComputedStyle(el).backgroundColor))
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('so-equipment-notes.png'), fullPage: true })
  })

  test(`VS16 (happy): booking identity precedes venue and the link has even vertical gaps at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'venue-staff')
    await page.goto('/venue-staff/bookings/booking?from=booking-approvals')
    const card = page.getByRole('region', { name: 'Other bookings for this event' }).locator('.booking-context-card')
    await expect(card).toContainText('Booking ID: 7aaa6eb7')
    const gaps = await card.evaluate(el => {
      const id = el.querySelector('.booking-related-card__id')!.getBoundingClientRect()
      const status = el.querySelector('.booking-status-tag')!.getBoundingClientRect()
      const title = el.querySelector('h3')!.getBoundingClientRect(), button = el.querySelector('a')!.getBoundingClientRect()
      const timings = el.querySelector('dl')!.getBoundingClientRect()
      return { sameRow: Math.abs(id.top - status.top), beforeVenue: title.top > id.top, above: button.top - title.bottom, below: timings.top - button.bottom }
    })
    expect(gaps.sameRow).toBeLessThan(2); expect(gaps.beforeVenue).toBe(true)
    expect(Math.abs(gaps.above - gaps.below)).toBeLessThan(2)
    await page.screenshot({ path: info.outputPath('related-booking.png'), fullPage: true })
  })

  test(`ECL-C1 (happy): coordinator dropdown has balanced text and arrow insets at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 }); await seed(page, 'coordinator-lead')
    await page.goto('/coordinator-lead/unassigned-requests/r1')
    await page.getByRole('button', { name: 'Assign', exact: true }).click()
    await expect(page.getByLabel('Event Coordinator', { exact: true })).toHaveCSS('padding-left', '12px')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath('assignment.png'), fullPage: true })
  })
}
