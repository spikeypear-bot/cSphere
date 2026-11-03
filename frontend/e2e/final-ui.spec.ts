// @stories EO03 EC-NEW3 EC07 TS09 TS01
import { expect, test, type Page } from '@playwright/test'

const event = {
  eventId: 'event-1', eventName: 'Town Hall', purpose: 'Quarterly update', description: 'All hands',
  startDatetime: '2030-03-10T01:00:00Z', endDatetime: '2030-03-10T04:00:00Z', expectedAttendance: 150,
  venueRequirements: 'Accessible theatre seating', equipmentRequirements: 'Presentation equipment',
  equipmentItems: [{ equipmentId: 'mic', quantity: 3 }], accessibilityNeeds: ['none'], requiredFacilities: [],
  registrationNeeds: false, status: 'pending', coordinatorName: 'ec1', coordinatorEmail: null,
}
const change = {
  changeRequestId: 'change-1', requestId: 'request-1', eventId: event.eventId, eventName: event.eventName,
  organisation: 'Acme', currentStatus: 'pending', status: 'pending', closedReason: null,
  reason: 'More attendees', requestedByName: 'eo1', requestedAt: '2026-11-01T00:00:00Z',
  decidedByName: null, decidedAt: null, decisionReason: null,
  changes: [{ field: 'expectedAttendance', label: 'Expected attendance', before: 150, after: 200 }],
  impact: [], followUps: [], canDecide: true, canWithdraw: false,
}
const statuses = ['processing', 'approved', 'attention', 'rejected', 'superseded']

async function fixture(page: Page, role: string, approvalFails = false) {
  await page.addInitScript(role => localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'fixture', refreshToken: 'fixture', userId: 'user-1', username: 'fixture', role, organisation: 'Acme',
  })), role)
  let approved = false
  let submitted = false
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    let json: unknown = []
    if (path === '/api/events/event-1') json = approved ? { ...event, expectedAttendance: 200, eventName: 'Approved Town Hall' } : event
    if (path === '/api/event-requests/request-1') json = { ...event, requestId: 'request-1', requestType: 'C', status: 'approved' }
    if (path === '/api/equipment/catalogue') json = [{ equipmentId: 'mic', equipmentName: 'Microphone', totalQuantity: 20 }]
    if (path === '/api/change-requests/change-1') json = change
    if (path === '/api/change-requests') {
      if (route.request().method() === 'POST') submitted = true
      json = approved ? [{ ...change, status: 'approved' }] : submitted ? [{ ...change, canWithdraw: true }] : []
      if (route.request().method() === 'POST') json = change
    }
    if (path === '/api/change-requests/change-1/approve') {
      if (approvalFails) return route.fulfill({ status: 500, json: { message: 'Approval could not be saved' } })
      approved = true
      json = { ...change, status: 'approved', canDecide: false }
    }
    if (path === '/api/events/event-1/readiness') json = { approvalStatus: 'not_reviewed', items: [], completedItems: 0, totalItems: 0 }
    if (path === '/api/events/event-1/lifecycle') json = { journeyStatus: { code: 'PLANNING' }, items: [], blockers: [] }
    if (path === '/api/equipment-requests') {
      const filter = url.searchParams.get('status')
      json = statuses.filter(status => filter === 'all' || filter === status || (filter === 'approved' && status === 'attention')).map(status => ({
        requestId: status, eventId: 'event-' + status, eventName: 'Event ' + status, status,
        eventStart: event.startDatetime, eventEnd: event.endDatetime, technicalRequirement: 'Stage',
        items: [{ equipmentId: 'mic', equipmentName: 'Microphone', quantity: 3 }],
      }))
    }
    if (path.endsWith('/notifications/unread-count')) json = { count: 0 }
    await route.fulfill({ json })
  })
}

async function noPageOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
}

for (const width of [1440, 390]) {
  test(`EO03 (happy): change form and proposal remain readable at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await fixture(page, 'organiser')
    await page.goto('/organiser/requests/request-1/change')
    await expect(page.getByRole('heading', { name: 'Venue and equipment requirements' })).toBeVisible()
    await page.getByRole('spinbutton', { name: 'Microphone quantity', exact: true }).fill('4')
    await noPageOverflow(page)
    await page.screenshot({ path: info.outputPath('eo-form.png'), fullPage: true })
    await page.getByRole('button', { name: 'Review the change' }).click()
    await expect(page.getByRole('table')).toContainText('3 × Microphone')
    await expect(page.getByRole('table')).toContainText('4 × Microphone')
    await page.getByLabel('Why is this change needed?').fill('One more microphone for the panel')
    await noPageOverflow(page)
    await page.screenshot({ path: info.outputPath('eo-proposal.png'), fullPage: true })
    await page.getByRole('button', { name: 'Send change request' }).click()
    await expect(page).toHaveURL(/\/organiser\/requests\/request-1\/changes$/)
    await expect(page.getByText(/Change request sent/)).toBeVisible()
  })

  test(`EC-NEW3 (happy): approval opens freshly approved event details at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await fixture(page, 'coordinator')
    await page.goto('/coordinator/change-requests/change-1')
    await expect(page.getByRole('table')).toContainText('200')
    await noPageOverflow(page)
    await page.screenshot({ path: info.outputPath('ec-review.png'), fullPage: true })
    await page.getByRole('button', { name: 'Approve and apply the change' }).click()
    await expect(page).toHaveURL(/\/coordinator\/events\/event-1$/)
    await expect(page.getByRole('heading', { name: 'Approved Town Hall' })).toBeVisible()
    await expect(page.getByText('Change approved and applied. The organiser has been told.')).toBeVisible()
  })

  test(`EC07 (happy): equipment items, quantities and approved requirements at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await fixture(page, 'coordinator')
    await page.goto('/coordinator/events/event-1/equipment-request')
    await expect(page.getByRole('spinbutton', { name: 'Microphone quantity', exact: true })).toHaveValue('3')
    await expect(page.getByRole('button', { name: 'Edit equipment' })).toBeVisible()
    await page.getByRole('spinbutton', { name: 'Microphone quantity', exact: true }).fill('2')
    await expect(page.getByRole('button', { name: 'Increase Microphone quantity' })).toBeVisible()
    await noPageOverflow(page)
    await page.screenshot({ path: info.outputPath('ec-equipment.png'), fullPage: true })
  })

  test(`TS09 TS01 (happy): status badges navigate supported request states at ${width}px`, { tag: '@ui' }, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await fixture(page, 'technical-support')
    await page.goto('/technical-support/requests?status=all')
    const filters = page.getByRole('group', { name: 'Filter equipment requests by status' })
    await expect(filters.getByRole('button', { name: 'All 5', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await filters.getByRole('button', { name: 'Needs reassessment 1' }).click()
    await expect(page).toHaveURL(/status=attention$/)
    await expect(page.getByRole('link', { name: 'Event attention' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Event approved' })).toHaveCount(0)
    await noPageOverflow(page)
    await page.screenshot({ path: info.outputPath('ts-status.png'), fullPage: true })
  })
}

test('EC-NEW3 (failure): failed approval stays on the review page', { tag: '@ui' }, async ({ page }) => {
  await fixture(page, 'coordinator', true)
  await page.goto('/coordinator/change-requests/change-1')
  await page.getByRole('button', { name: 'Approve and apply the change' }).click()
  await expect(page.getByRole('alert')).toHaveText('Approval could not be saved')
  await expect(page).toHaveURL(/\/coordinator\/change-requests\/change-1$/)
})
