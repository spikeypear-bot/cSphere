// @stories EO-C2 EO02
import { expect, test } from '@playwright/test'
import { apiToken, evidence, signedIn, uniqueName } from './support.ts'

// EO-C2 through the browser and the packaged backend: an organiser cannot
// send a request that ends when it starts, starts in the past or expects
// nobody; what they type is Singapore time; and the server refuses the same
// request even when the page is bypassed. Expected values come from the ACs.

const HOUR = 3_600_000
const DAY = 24 * HOUR

/** "YYYY-MM-DDTHH:mm" in Singapore time, `days` from today. */
function singaporeLocal(days: number, time: string): string {
  return `${new Date(Date.now() + 8 * HOUR + days * DAY).toISOString().slice(0, 10)}T${time}`
}

test('an organiser is shown each date, time and attendance problem and can only submit once it is fixed (EO-C2 AC1, AC2, AC4, AC5, AC6)', async ({ browser, request }) => {
  test.setTimeout(90_000)
  const eventName = uniqueName('E2E Rules Gala')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(eventName)
  await page.getByLabel('Purpose').fill('Quarterly all-hands update')
  await page.getByRole('button', { name: 'Next' }).click()
  const start = page.getByLabel('Start date & time')
  const end = page.getByLabel('End date & time')
  const attendance = page.getByLabel('Expected attendance')
  const next = page.getByRole('button', { name: 'Next' })

  await test.step('AC5: times are entered in Singapore time, and say so', async () => {
    await expect(page.getByText('Singapore time (GMT+8)').first()).toBeVisible()
  })

  await test.step('AC1: an event that ends when it starts is explained next to the end and blocks Next', async () => {
    await start.fill(singaporeLocal(7, '10:00'))
    await end.fill(singaporeLocal(7, '10:00'))
    await attendance.fill('150')
    await expect(page.getByText('The end date & time must be after the start date & time.')).toBeVisible()
    await expect(end).toHaveAttribute('aria-invalid', 'true')
    await expect(next).toBeDisabled()
    await evidence(page, 'EO-C2-TC1')
  })

  await test.step('AC2: a start in the past is explained next to the start', async () => {
    await start.fill(singaporeLocal(-1, '10:00'))
    await end.fill(singaporeLocal(-1, '12:00'))
    await expect(page.getByText('The start date & time must be in the future.')).toBeVisible()
    await expect(next).toBeDisabled()
    await evidence(page, 'EO-C2-TC2')
  })

  await test.step('AC4: nobody attending is explained', async () => {
    await start.fill(singaporeLocal(7, '10:00'))
    await end.fill(singaporeLocal(7, '12:00'))
    await attendance.fill('0')
    await expect(page.getByText('Expected attendance must be at least 1.')).toBeVisible()
    await expect(next).toBeDisabled()
    await evidence(page, 'EO-C2-TC3a')
  })

  await test.step('once fixed, the request is submitted', async () => {
    await attendance.fill('150')
    await expect(next).toBeEnabled()
    await next.click()
    await page.getByLabel('Venue requirements').fill('Theatre seating for 150')
    await page.getByRole('group', { name: 'Accessibility needs' })
      .getByRole('button', { name: 'No accessibility requirements needed' }).click()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.getByRole('group', { name: 'Equipment requirements' })).toBeVisible()
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(page.getByText(/\(Singapore time\)/).first()).toBeVisible()
    await evidence(page, 'EO-C2-TC4')
    await page.getByRole('button', { name: 'Submit for review' }).click()
    await expect(page.getByText('Your event request has been submitted successfully.')).toBeVisible()
  })

  await test.step('AC5: 10:00 typed is stored as 10:00 in Singapore (02:00 UTC), whatever the browser\'s zone', async () => {
    const token = await apiToken(request, 'eo1')
    const response = await request.get('/api/event-requests', { headers: { Authorization: `Bearer ${token}` } })
    const sent = (await response.json() as { eventName: string; startDatetime: string }[])
      .find((r) => r.eventName === eventName)
    expect(sent?.startDatetime).toBe(`${singaporeLocal(7, '02:00').slice(0, 10)}T02:00:00Z`)
  })
})

test('the server refuses an invalid request even when the page is bypassed, and says which field (EO-C2 AC8)', async ({ request }) => {
  const token = await apiToken(request, 'eo1')
  const headers = { Authorization: `Bearer ${token}` }
  const lastYear = new Date(Date.now() - 365 * DAY).toISOString()
  const draft = await request.post('/api/event-requests', { headers, data: {
    eventName: uniqueName('E2E Bypass Gala'), purpose: 'Testing', startDatetime: lastYear, endDatetime: lastYear,
    expectedAttendance: 0, venueRequirements: 'Theatre', accessibilityNeeds: ['none'], registrationNeeds: false,
  } })
  expect(draft.status()).toBe(201)
  const { requestId } = await draft.json() as { requestId: string }

  const submitted = await request.post(`/api/event-requests/${requestId}/submit`, { headers })

  expect(submitted.status()).toBe(422)
  expect((await submitted.json() as { fieldErrors: Record<string, string> }).fieldErrors).toEqual({
    startDatetime: 'The start date & time must be in the future.',
    endDatetime: 'The end date & time must be after the start date & time.',
    expectedAttendance: 'Expected attendance must be at least 1.',
  })
})
