// @stories EO-C2 EO01 EO26
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, assignedByApi, evidence, signedIn, submittedRequestByApi, uniqueName } from './support.ts'

// EO-C2 functional test cases not covered by request-rules.spec.ts (Sheet:
// Test Cases - Sprint 4, EO-C2-TC3, TC5, TC7). Expected wording comes from
// the EO-C2 ACs and the messages the server and page share.

type Request = { startDatetime: string | null; endDatetime: string | null; status: string }

test('EO-C2 AC4 (negative): a negative or decimal attendance cannot be entered on the page or submitted by API', async ({ browser, request }) => {
  test.setTimeout(90_000)
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(uniqueName('EO-C2 Attendance Gala'))
  await page.getByLabel('Purpose').fill('Quarterly all-hands update')
  await page.getByRole('button', { name: 'Next' }).click()
  await page.getByLabel('Start date & time').fill('2027-12-01T09:00')
  await page.getByLabel('End date & time').fill('2027-12-01T12:00')
  const attendance = page.getByLabel('Expected attendance')

  await test.step('the whole-number field does not take a minus sign, so -5 cannot be entered', async () => {
    await attendance.pressSequentially('-5')
    await expect(attendance).not.toHaveValue(/-/)
    await evidence(page, 'EO-C2-TC3b')
  })

  await test.step('the whole-number field does not take a decimal point', async () => {
    await attendance.fill('12')
    await attendance.pressSequentially('.5')
    await expect(attendance).not.toHaveValue(/\./)
  })

  await test.step('the server refuses -5 and 12.5 even when the page is bypassed', async () => {
    const headers = { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }
    for (const expectedAttendance of [-5, 12.5]) {
      const draft = await request.post('/api/event-requests', { headers, data: {
        eventName: uniqueName('EO-C2 Bypass Gala'), purpose: 'Testing', startDatetime: '2027-12-01T09:00:00+08:00',
        endDatetime: '2027-12-01T12:00:00+08:00', expectedAttendance, venueRequirements: 'Theatre',
        accessibilityNeeds: ['none'], registrationNeeds: false } })
      if (!draft.ok()) continue // refused already when saving the value
      const submitted = await request.post(`/api/event-requests/${(await draft.json()).requestId}/submit`, { headers })
      expect(submitted.ok(), `attendance ${expectedAttendance} was accepted`).toBeFalsy()
    }
  })
})

test('EO-C2 AC7 (happy): a draft with an end before its start can still be saved; the checks apply only when submitting', async ({ browser, request }) => {
  test.setTimeout(90_000)
  const eventName = uniqueName('EO-C2 Draft Gala')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(eventName)
  await page.getByLabel('Purpose').fill('Draft with unfinished times')
  await page.getByRole('button', { name: 'Save as draft' }).click()
  await expect(page.getByText(/saved/i).first()).toBeVisible()

  const token = await apiToken(request, 'eo1')
  const requests = await apiGet<{ requestId: string; eventName: string }[]>(request, token, '/api/event-requests')
  const requestId = requests.find((r) => r.eventName === eventName)!.requestId
  const saved = await request.put(`/api/event-requests/${requestId}`, { headers: { Authorization: `Bearer ${token}` },
    data: { eventName, purpose: 'Draft with unfinished times', startDatetime: '2027-12-02T12:00:00+08:00',
      endDatetime: '2027-12-02T09:00:00+08:00', expectedAttendance: 0 } })
  expect(saved.ok(), `saving an invalid draft: ${saved.status()} ${await saved.text()}`).toBeTruthy()
  const draft = await apiGet<Request>(request, token, `/api/event-requests/${requestId}`)
  expect(draft.status.toLowerCase()).toBe('draft')

  await page.goto('/organiser')
  await expect(page.getByRole('heading', { name: eventName })).toBeVisible()
  await evidence(page, 'EO-C2-TC5')
  const submitted = await request.post(`/api/event-requests/${requestId}/submit`, { headers: { Authorization: `Bearer ${token}` } })
  expect(submitted.status()).toBe(422)
})

test('EO-C2 AC3 (negative): resubmitting after clarification applies the same date and time rules', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('EO-C2 Clarify Gala')
  const requestId = await submittedRequestByApi(request, eventName, '2027-12-03T09:00:00+08:00', '2027-12-03T12:00:00+08:00')
  await assignedByApi(request, requestId)
  const asked = await request.post(`/api/event-requests/${requestId}/clarifications`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` },
    data: { message: 'Please confirm the timings', flaggedFields: ['startDatetime', 'endDatetime'], fieldQuestions: {} } })
  expect(asked.ok(), await asked.text()).toBeTruthy()

  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${requestId}/respond`)
  await page.getByLabel('End date & time').fill('2027-12-03T08:00')
  await page.locator('#clarify-response').fill('Moved the end earlier')
  await expect(page.getByText('The end date & time must be after the start date & time.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
  await evidence(page, 'EO-C2-TC7')

  const token = await apiToken(request, 'eo1')
  const current = await apiGet<Record<string, unknown>>(request, token, `/api/event-requests/${requestId}`)
  const resubmitted = await request.post(`/api/event-requests/${requestId}/resubmit`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { response: 'Bypassing the page', details: { ...current, endDatetime: '2027-12-03T08:00:00+08:00' } } })
  expect(resubmitted.ok()).toBeFalsy()
  const after = await apiGet<Request>(request, token, `/api/event-requests/${requestId}`)
  expect(Date.parse(after.endDatetime!)).toBe(Date.parse('2027-12-03T12:00:00+08:00'))
})
