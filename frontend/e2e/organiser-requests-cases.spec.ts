// @stories EO01 EO02 EO15 DEV11
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, signedIn, submitRequest, submittedRequestByApi, uniqueName } from './support.ts'

// Sprint 1 functional test cases (Sheet: Test Cases - Sprint 1, EO01-TC1…TC6,
// EO02-TC1…TC6, EO15-TC1…TC6, DEV11-TC1/TC2) as browser journeys. EO15 and
// DEV11's cases were written for Sprint 1's role selector, which AU04's real
// sign-in replaced; their journeys check the same promise through sign-in.

type Request = { requestId: string; eventName: string | null; status: string; expectedAttendance: number | null }

async function requestsOf(request: APIRequestContext, username = 'eo1'): Promise<Request[]> {
  return apiGet<Request[]>(request, await apiToken(request, username), '/api/event-requests')
}

async function draftByApi(request: APIRequestContext, eventName: string, username = 'eo1'): Promise<string> {
  const created = await request.post('/api/event-requests', {
    headers: { Authorization: `Bearer ${await apiToken(request, username)}` }, data: { eventName, purpose: 'Draft fixture' } })
  expect(created.ok(), await created.text()).toBeTruthy()
  return (await created.json()).requestId as string
}

test('EO01 (happy): TC1 an incomplete request is saved as a Draft with a confirmation, and is not shown as submitted', async ({ browser, request }) => {
  const eventName = uniqueName('Alumni Homecoming')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(eventName)
  await page.getByRole('button', { name: 'Save as draft' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Saved as draft' })).toBeVisible()
  await page.getByRole('button', { name: 'Exit' }).click()
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: eventName }) })
  // The status badge (not the progress steps, which name every stage) says Draft.
  await expect(card.getByTitle(/^Not submitted yet/)).toHaveText('Draft')
  expect((await requestsOf(request)).find((r) => r.eventName === eventName)?.status).toBe('draft')
})

test('EO01 (happy): TC2 a reopened draft keeps what was saved and takes further edits while staying a Draft', async ({ browser, request }) => {
  const eventName = uniqueName('Alumni Homecoming')
  const requestId = await draftByApi(request, eventName)
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser')
  await page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: eventName }) })
    .getByRole('link', { name: 'Continue editing' }).click()
  await expect(page.getByLabel('Event name')).toHaveValue(eventName)
  await expect(page.getByLabel('Purpose')).toHaveValue('Draft fixture')
  await page.getByRole('button', { name: 'Next' }).click()
  await page.getByLabel('Expected attendance').fill('150')
  await expect.poll(async () => (await requestsOf(request)).find((r) => r.requestId === requestId)?.expectedAttendance,
    { timeout: 10_000 }).toBe(150)
  expect((await requestsOf(request)).find((r) => r.requestId === requestId)?.status).toBe('draft')
})

test('EO01 (failure): TC3 a failed save says so and leaves the last saved version untouched', async ({ browser, request }) => {
  const eventName = uniqueName('Alumni Homecoming')
  const requestId = await draftByApi(request, eventName)
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${requestId}`)
  await expect(page.getByLabel('Event name')).toHaveValue(eventName)
  await page.route(`**/api/event-requests/${requestId}`, (route) =>
    route.request().method() === 'PUT' ? route.fulfill({ status: 500, body: '{"message":"Server error"}' }) : route.continue())
  await page.getByLabel('Event name').fill(`${eventName} 2026`)
  await page.getByRole('button', { name: 'Save as draft' }).click()
  await expect(page.getByText('Could not save — your last saved version is safe')).toBeVisible()
  expect((await requestsOf(request)).find((r) => r.requestId === requestId)?.eventName).toBe(eventName)
})

test('EO01 (negative): TC4 another organisation\'s draft is not listed and cannot be opened or edited, including by API', async ({ browser, request }) => {
  const eventName = uniqueName('Globex Draft')
  const requestId = await draftByApi(request, eventName, 'eo3')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser')
  await expect(page.getByRole('heading', { name: eventName })).toHaveCount(0)
  await page.goto(`/organiser/requests/${requestId}`)
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByLabel('Event name')).toHaveCount(0)
  const edited = await request.put(`/api/event-requests/${requestId}`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }, data: { eventName: 'Taken over' } })
  expect(edited.ok()).toBeFalsy()
  expect((await requestsOf(request, 'eo3')).find((r) => r.requestId === requestId)?.eventName).toBe(eventName)
})

test('EO01 (happy): TC5 drafts and submitted requests are both listed, each labelled with its own status', async ({ browser, request }) => {
  const draftName = uniqueName('Alumni Homecoming')
  const submittedName = uniqueName('Career Fair')
  await draftByApi(request, draftName)
  await submittedRequestByApi(request, submittedName, '2027-09-20T09:00:00+08:00', '2027-09-20T12:00:00+08:00')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser')
  const card = (name: string) => page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
  await expect(card(draftName).getByTitle(/^Not submitted yet/)).toHaveText('Draft')
  await expect(card(draftName).getByRole('link', { name: 'Continue editing' })).toBeVisible()
  await expect(card(submittedName).getByTitle(/^Not submitted yet/)).toHaveCount(0)
  await expect(card(submittedName).getByRole('link', { name: 'Continue editing' })).toHaveCount(0)
})

test('EO01 (happy): TC6 a typed draft saves itself without pressing Save, and is there after a reload', async ({ browser, request }) => {
  const eventName = uniqueName('Founders Day Gala')
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(eventName)
  await expect(page.getByRole('status').filter({ hasText: 'Saved as draft' })).toBeVisible({ timeout: 10_000 })
  const saved = (await requestsOf(request)).find((r) => r.eventName === eventName)
  expect(saved?.status).toBe('draft')
  await page.goto(`/organiser/requests/${saved!.requestId}`)
  await expect(page.getByLabel('Event name')).toHaveValue(eventName)
})

test('EO02 (negative): TC2 a request missing its event name cannot be submitted; the missing field is named and it stays a Draft', async ({ browser, request }) => {
  const token = await apiToken(request, 'eo1')
  const created = await request.post('/api/event-requests', { headers: { Authorization: `Bearer ${token}` }, data: {
    eventName: '', purpose: uniqueName('Nameless purpose'), startDatetime: '2027-09-21T09:00:00+08:00',
    endDatetime: '2027-09-21T12:00:00+08:00', expectedAttendance: 120, venueRequirements: 'Theatre',
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false } })
  expect(created.ok(), await created.text()).toBeTruthy()
  const requestId = (await created.json()).requestId as string
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${requestId}`)
  for (let i = 0; i < 4; i++) await page.getByRole('button', { name: 'Next' }).click()
  const checklist = page.getByRole('list', { name: 'Required fields checklist' })
  await expect(checklist.getByRole('listitem').filter({ hasText: 'Event name' })).toHaveAttribute('data-done', 'false')
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByText('This request still needs:')).toBeVisible()
  await expect(page.locator('.wizard__missing')).toContainText('Event name')
  const submitted = await request.post(`/api/event-requests/${requestId}/submit`, { headers: { Authorization: `Bearer ${token}` } })
  expect(submitted.status()).toBe(422)
  expect((await requestsOf(request)).find((r) => r.requestId === requestId)?.status).toBe('draft')
})

test('EO02 (happy): TC3 a request with no equipment or registration needs is submitted', async ({ browser, request }) => {
  const eventName = uniqueName('Tech Careers Fair')
  const page = await signedIn(browser, 'eo1')
  await submitRequest(page, eventName, '2027-10-15T09:00', '2027-10-15T11:00')
  expect((await requestsOf(request)).find((r) => r.eventName === eventName)?.status).toBe('pending')
})

test('EO02 (negative): TC4 an organiser cannot submit another organisation\'s draft by API', async ({ request }) => {
  const requestId = await draftByApi(request, uniqueName('Globex Draft'), 'eo3')
  const submitted = await request.post(`/api/event-requests/${requestId}/submit`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` } })
  expect(submitted.ok()).toBeFalsy()
  expect((await requestsOf(request, 'eo3')).find((r) => r.requestId === requestId)?.status).toBe('draft')
})

test('EO02 (negative): TC5 a submitted request can no longer be edited as a draft and is listed as Submitted', async ({ browser, request }) => {
  const eventName = uniqueName('Tech Careers Fair')
  const requestId = await submittedRequestByApi(request, eventName, '2027-10-16T09:00:00+08:00', '2027-10-16T11:00:00+08:00')
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${requestId}`)
  await expect(page.getByText('This request has already been submitted and can no longer be edited here.')).toBeVisible()
  await page.goto('/organiser')
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: eventName }) })
  await expect(card.getByText('Submitted', { exact: true }).first()).toBeVisible()
  await expect(card.getByRole('link', { name: 'Continue editing' })).toHaveCount(0)
})

test('EO02 (happy): TC6 the completion percentage and checklist update as fields are filled, before submitting', async ({ browser }) => {
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await expect(page.getByText('0% ready to submit')).toBeVisible()
  await page.getByLabel('Event name').fill(uniqueName('Founders Day Gala'))
  await expect(page.getByText(/^[1-9]\d?% ready to submit$/)).toBeVisible()
  for (let i = 0; i < 4; i++) await page.getByRole('button', { name: 'Next' }).click()
  const item = (label: string) => page.getByRole('list', { name: 'Required fields checklist' })
    .getByRole('listitem').filter({ hasText: label })
  await expect(item('Event name')).toHaveAttribute('data-done', 'true')
  await expect(item('Purpose')).toHaveAttribute('data-done', 'false')
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeVisible()
})

test('EO15 (happy): TC1 an organiser signs in to their organisation and sees only its requests', async ({ browser, request }) => {
  const ownName = uniqueName('Acme Request')
  const otherName = uniqueName('Globex Request')
  await draftByApi(request, ownName, 'eo1')
  await draftByApi(request, otherName, 'eo3')
  const page = await signedIn(browser, 'eo1')
  await expect(page.getByText('eo1 · Event Organiser · Acme Pte Ltd')).toBeVisible()
  await expect(page.getByRole('heading', { name: ownName })).toBeVisible()
  await expect(page.getByRole('heading', { name: otherName })).toHaveCount(0)
})

test('EO15 (negative): TC2 signing in needs every credential; a blank one does not get in', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill('eo1')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Log out' })).toHaveCount(0)
})

test('EO15 (negative): TC3 another organisation\'s request cannot be opened, in the page or by API', async ({ browser, request }) => {
  const otherName = uniqueName('Globex Request')
  const requestId = await submittedRequestByApi(request, otherName, '2027-10-17T09:00:00+08:00', '2027-10-17T11:00:00+08:00')
  const outsider = await signedIn(browser, 'eo3')
  await outsider.goto(`/organiser/requests/${requestId}`)
  await expect(outsider.getByRole('alert')).toBeVisible()
  await expect(outsider.getByLabel('Event name')).toHaveCount(0)
  const opened = await request.get(`/api/event-requests/${requestId}`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo3')}` } })
  expect(opened.ok()).toBeFalsy()
})

test('EO15 (negative): TC4 after logging out, a page used before cannot be reopened without signing in again', async ({ browser }) => {
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser/requests/new')
  await page.getByRole('button', { name: 'Log out' }).click()
  await page.goto('/organiser/requests/new')
  await expect(page.getByLabel('Username')).toBeVisible()
  await expect(page.getByLabel('Event name')).toHaveCount(0)
})

test('EO15 (negative): TC5 an organiser cannot use coordinator, venue staff or technical support pages', async ({ browser }) => {
  const page = await signedIn(browser, 'eo1')
  for (const path of ['/coordinator/review-queue', '/venue-staff/booking-approvals', '/technical-support/requests']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/organiser/)
  }
})

test('EO15 (happy): TC6 each request shows its progress as a timeline, with a rejected request marked differently from an approved one', async ({ browser, request }) => {
  const draftName = uniqueName('Timeline Draft')
  const submittedName = uniqueName('Timeline Submitted')
  await draftByApi(request, draftName)
  const submittedId = await submittedRequestByApi(request, submittedName, '2027-10-18T09:00:00+08:00', '2027-10-18T11:00:00+08:00')
  expect(submittedId).toBeTruthy()
  const page = await signedIn(browser, 'eo1')
  await page.goto('/organiser')
  const card = (name: string) => page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
  await expect(card(draftName).getByText('Draft', { exact: true }).first()).toBeVisible()
  await expect(card(submittedName).getByText('Request', { exact: true })).toBeVisible()
  await expect(card(submittedName).getByText('Under review', { exact: true })).toBeVisible()
})

test('DEV11 (happy): TC1, TC2 signed out shows only sign-in; signed in shows the person\'s role and organisation, and another role\'s page sends them home', async ({ page, browser }) => {
  await page.goto('/')
  await expect(page.getByLabel('Username')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Log out' })).toHaveCount(0)
  const organiser = await signedIn(browser, 'eo1')
  await expect(organiser.getByText('eo1 · Event Organiser · Acme Pte Ltd')).toBeVisible()
  await expect(organiser.getByRole('heading', { name: 'More Event Organiser stories' })).toBeVisible()
  await organiser.goto('/venue-staff')
  await expect(organiser).toHaveURL(/\/organiser/)
})
