// @stories EO26 EC01 EC02
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiGet, apiToken, assignedByApi, openBell, planningEventByApi, signedIn, submittedRequestByApi, uniqueName,
} from './support.ts'

// Sprint 2 functional test cases (Sheet: Test Cases - Sprint 2, EO26-TC1…TC13,
// EC01-TC1…TC13, EC02-TC1…TC15) as browser journeys, with fixtures through
// the API. Cases that change the database directly (EC01-TC11's UPDATE and
// DELETE, EC02-TC7's blanked purpose) are covered by backend integration tests.

type Request = { requestId: string; eventName: string; status: string; expectedAttendance: number; purpose: string
  eventId: string | null; coordinatorId: string | null }

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

async function requestAs(request: APIRequestContext, requestId: string, username = 'eo1'): Promise<Request> {
  return apiGet<Request>(request, await apiToken(request, username), `/api/event-requests/${requestId}`)
}

/** A request of eo1's, submitted and assigned to ec1. */
async function assignedRequest(request: APIRequestContext, prefix: string, day = '2027-07-05'): Promise<{ id: string; name: string }> {
  const name = uniqueName(prefix)
  const id = await submittedRequestByApi(request, name, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  await assignedByApi(request, id)
  return { id, name }
}

/** ec1 asks for clarification through the API (fixture, not the journey). */
async function clarifyByApi(request: APIRequestContext, requestId: string, message = 'Please confirm the numbers',
  flaggedFields: string[] = ['expectedAttendance']) {
  const asked = await request.post(`/api/event-requests/${requestId}/clarifications`, {
    headers: await auth(request, 'ec1'), data: { message, flaggedFields, fieldQuestions: {} } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
}

async function resubmitByApi(request: APIRequestContext, requestId: string, response: string, patch: Record<string, unknown> = {}) {
  const details = { ...(await requestAs(request, requestId) as unknown as Record<string, unknown>), ...patch }
  return request.post(`/api/event-requests/${requestId}/resubmit`, { headers: await auth(request, 'eo1'), data: { response, details } })
}

// ---------------------------------------------------------------- EO26

test('EO26 (happy): TC1 a request waiting for clarification is marked, shows the latest question and offers Respond', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Multi Round Seminar')
  await clarifyByApi(request, id, 'How many guests exactly?')
  const page = await signedIn(browser, 'eo1')
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
  await expect(card.getByText('Clarification Required').first()).toBeVisible()
  await expect(card.getByText('How many guests exactly?')).toBeVisible()
  await expect(card.getByRole('link', { name: 'Respond' })).toBeVisible()
})

test('EO26 (happy): TC2, TC3 the organiser sees every round in order and the flagged fields highlighted with the question', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Two Round Workshop')
  await clarifyByApi(request, id, 'First: how many attendees?')
  expect((await resubmitByApi(request, id, 'It is 150 people')).ok()).toBeTruthy()
  const asked = await request.post(`/api/event-requests/${id}/clarifications`, { headers: await auth(request, 'ec1'),
    data: { message: 'Second: please check the end time', flaggedFields: ['endDatetime', 'venueRequirements'],
      fieldQuestions: { endDatetime: 'Does it really end at noon?', venueRequirements: 'Is theatre seating right?' } } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${id}/respond`)
  const history = page.getByText('Conversation & history').locator('..')
  await expect(page.getByText('First: how many attendees?')).toBeVisible()
  await expect(page.getByText('It is 150 people')).toBeVisible()
  await expect(page.getByText('Second: please check the end time').first()).toBeVisible()
  expect(await history.count()).toBeGreaterThan(0)
  const flagged = page.locator('.clarify__field[data-flagged="true"]')
  await expect(flagged).toHaveCount(2)
  await expect(flagged.first().getByText('Requested by your coordinator')).toBeVisible()
  await expect(page.getByText('“Does it really end at noon?”')).toBeVisible()
})

test('EO26 (negative): TC4 approved and under-review requests cannot be edited, in the page or by API', async ({ browser, request }) => {
  const eventId = await planningEventByApi(request, uniqueName('Acme Approved Already'), '2027-07-06T09:00:00+08:00', '2027-07-06T12:00:00+08:00')
  const eo = await auth(request, 'eo1')
  const approved = (await apiGet<Request[]>(request, eo.Authorization.slice(7), '/api/event-requests')).find((r) => r.eventId === eventId)!
  const underReview = await assignedRequest(request, 'Acme Network Failure Talk')
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${approved.requestId}`)
  await expect(page.getByText('This request has been approved and is now being planned, so it can no longer be edited here.')).toBeVisible()
  await page.goto(`/organiser/requests/${approved.requestId}/respond`)
  await expect(page.getByText('This request is not waiting for clarification any more.')).toBeVisible()
  expect((await request.put(`/api/event-requests/${underReview.id}`, { headers: eo, data: { eventName: 'Renamed' } })).ok()).toBeFalsy()
  expect((await resubmitByApi(request, approved.requestId, 'Trying anyway')).ok()).toBeFalsy()
  expect((await requestAs(request, underReview.id)).eventName).toBe(underReview.name)
})

test('EO26 (happy): TC5 saving changes without resubmitting keeps them, and the request stays with the organiser', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Save Without Resubmit')
  await clarifyByApi(request, id)
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${id}/respond`)
  await page.getByLabel('Expected attendance').fill('145')
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByText('Saved. Your request is still with you until you resubmit.')).toBeVisible()
  const saved = await requestAs(request, id)
  expect(saved.expectedAttendance).toBe(145)
  expect(saved.status).toBe('clarification_required')
})

test('EO26 (boundary): TC6 the reply must be 1 to 2,000 characters; blank and spaces-only are refused', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Reply Limits')
  await clarifyByApi(request, id)
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${id}/respond`)
  await page.locator('#clarify-response').fill('     ')
  await expect(page.getByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
  for (const response of ['', ' ', 'x'.repeat(2001)]) {
    expect((await resubmitByApi(request, id, response)).ok(), `reply of ${response.length} characters`).toBeFalsy()
  }
  expect((await resubmitByApi(request, id, 'y'.repeat(2000))).ok()).toBeTruthy()
  const other = await assignedRequest(request, 'Acme One Character Reply')
  await clarifyByApi(request, other.id)
  expect((await resubmitByApi(request, other.id, 'k')).ok()).toBeTruthy()
})

test('EO26 (negative): TC7 resubmitting applies the same completeness and date checks as submitting', async ({ request }) => {
  const { id } = await assignedRequest(request, 'Acme Resubmit Validation')
  await clarifyByApi(request, id, 'Check the end', ['endDatetime'])
  const refused = await resubmitByApi(request, id, 'Fixed it', { purpose: '', expectedAttendance: null,
    endDatetime: '2027-07-05T08:00:00+08:00' })
  expect(refused.status()).toBe(422)
  expect((await requestAs(request, id)).status).toBe('clarification_required')
})

test('EO26 (happy): TC8, TC9 the organiser updates the flagged field and resubmits; the same coordinator is told and the history keeps every round', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Multi Round Seminar')
  await clarifyByApi(request, id, 'Can it start later?', ['startDatetime'])
  const page = await signedIn(browser, 'eo1')
  await page.goto(`/organiser/requests/${id}/respond`)
  await page.getByLabel('Start date & time').fill('2027-07-05T10:00')
  await page.locator('#clarify-response').fill('Moved the start to 10 am')
  await page.getByRole('button', { name: 'Save and resubmit' }).click()
  await expect.poll(async () => (await requestAs(request, id)).status).toBe('pending')
  const coordinator = await signedIn(browser, 'ec1')
  await expect((await openBell(coordinator)).getByRole('listitem')
    .filter({ hasText: `"${name}" was updated and resubmitted` })).toBeVisible()
  await coordinator.goto(`/coordinator/requests/${id}`)
  await expect(coordinator.getByText('Can it start later?').first()).toBeVisible()
  await expect(coordinator.getByText('Moved the start to 10 am').first()).toBeVisible()
  await expect(coordinator.getByText('What changed since you asked')).toBeVisible()
})

test('EO26 (negative): TC10 another organisation cannot open or answer the clarification', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Clarification Gala')
  await clarifyByApi(request, id)
  const outsider = await signedIn(browser, 'eo3')
  await outsider.goto(`/organiser/requests/${id}/respond`)
  await expect(outsider.getByText('Update your request')).toHaveCount(0)
  expect((await request.get(`/api/event-requests/${id}`, { headers: await auth(request, 'eo3') })).ok()).toBeFalsy()
  const details = await requestAs(request, id)
  const refused = await request.post(`/api/event-requests/${id}/resubmit`, { headers: await auth(request, 'eo3'),
    data: { response: 'Not mine', details } })
  expect(refused.ok()).toBeFalsy()
  expect((await requestAs(request, id)).status).toBe('clarification_required')
})

test('EO26 (happy): TC11 another organiser of the same organisation can open and answer it', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Shared Clarification')
  await clarifyByApi(request, id)
  const colleague = await signedIn(browser, 'eo2')
  await colleague.goto(`/organiser/requests/${id}/respond`)
  await expect(colleague.getByText('Update your request')).toBeVisible()
  await expect(colleague.getByRole('button', { name: 'Save and resubmit' })).toBeVisible()
})

test('EO26 (failure): TC12 if resubmitting fails the organiser is told and nothing changes', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Respond Failure Talk')
  await clarifyByApi(request, id)
  const page = await signedIn(browser, 'eo1')
  await page.route(`**/api/event-requests/${id}/resubmit`, (route) => route.abort())
  await page.goto(`/organiser/requests/${id}/respond`)
  await page.locator('#clarify-response').fill('Here is my answer')
  await page.getByRole('button', { name: 'Save and resubmit' }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  expect((await requestAs(request, id)).status).toBe('clarification_required')
})

test('EO26 (negative): TC13 coordinators, the Lead and Venue Staff cannot resubmit an organiser\'s request', async ({ request }) => {
  const { id } = await assignedRequest(request, 'Acme Save Without Resubmit')
  await clarifyByApi(request, id)
  const details = await requestAs(request, id)
  for (const username of ['ec1', 'ecl1', 'vs1']) {
    const refused = await request.post(`/api/event-requests/${id}/resubmit`, { headers: await auth(request, username),
      data: { response: 'Not mine to answer', details } })
    expect(refused.status(), username).toBe(403)
  }
})

// ---------------------------------------------------------------- EC01

test('EC01 (happy): TC1, TC2, TC3, TC4 the coordinator flags two fields and asks; the request waits for the organisers, its details untouched, and every Acme organiser is told', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Staff Wellness Day')
  const before = await requestAs(request, id)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('button', { name: 'Flag Expected attendance' }).click()
  await coordinator.getByRole('button', { name: 'Flag Start date & time' }).click()
  await coordinator.getByRole('button', { name: 'Request clarification (2)' }).click()
  await expect(coordinator.getByRole('list', { name: 'Flagged fields' }).getByRole('listitem')).toHaveCount(2)
  await coordinator.getByLabel(/about the Expected attendance/).fill('Is it 150 including staff?')
  await coordinator.getByLabel(/about the Start date & time/).fill('Could it start at 10?')
  await coordinator.getByRole('button', { name: 'Send clarification request' }).click()
  await expect(coordinator.getByText("Clarification sent. Acme Pte Ltd's organisers have been notified.")).toBeVisible()
  await expect(coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' })).toHaveCount(0)
  await expect(coordinator.getByText('Is it 150 including staff?').first()).toBeVisible()

  const after = await requestAs(request, id)
  expect(after.status).toBe('clarification_required')
  expect({ ...after, status: before.status }).toMatchObject({ eventName: before.eventName, purpose: before.purpose,
    expectedAttendance: before.expectedAttendance })
  for (const organiser of ['eo1', 'eo2']) {
    const page = await signedIn(browser, organiser)
    await expect((await openBell(page)).getByRole('listitem').filter({ hasText: `Clarification needed on "${name}"` })).toBeVisible()
  }
})

test('EC01 (boundary): TC5 the clarification message must be 1 to 2,000 characters', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Product Demo')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('button', { name: 'Request clarification' }).click()
  await coordinator.getByLabel('What do you need from the organiser?').fill('    ')
  await expect(coordinator.getByRole('button', { name: 'Send clarification request' })).toBeDisabled()
  const ec = await auth(request, 'ec1')
  for (const message of ['', ' ', 'x'.repeat(2001)]) {
    const refused = await request.post(`/api/event-requests/${id}/clarifications`, { headers: ec,
      data: { message, flaggedFields: [], fieldQuestions: {} } })
    expect(refused.ok(), `message of ${message.length} characters`).toBeFalsy()
  }
  const accepted = await request.post(`/api/event-requests/${id}/clarifications`, { headers: ec,
    data: { message: 'y'.repeat(2000), flaggedFields: [], fieldQuestions: {} } })
  expect(accepted.ok(), await accepted.text()).toBeTruthy()
})

test('EC01 (negative): TC6, TC7 clarification is refused for a request that is not Submitted, or not assigned to the coordinator', async ({ request }) => {
  const ec = await auth(request, 'ec1')
  const ask = (id: string, headers = ec) => request.post(`/api/event-requests/${id}/clarifications`, { headers,
    data: { message: 'A question', flaggedFields: [], fieldQuestions: {} } })
  const waiting = await assignedRequest(request, 'Acme Clarify Then Reject')
  await clarifyByApi(request, waiting.id)
  const eventId = await planningEventByApi(request, uniqueName('Acme Approved Already'), '2027-07-07T09:00:00+08:00', '2027-07-07T12:00:00+08:00')
  const approved = (await apiGet<Request[]>(request, await apiToken(request, 'eo1'), '/api/event-requests')).find((r) => r.eventId === eventId)!
  for (const id of [waiting.id, approved.requestId]) {
    const refused = await ask(id)
    expect(refused.ok()).toBeFalsy()
    expect((await refused.text()).length).toBeGreaterThan(0)
  }
  const elsewhere = await submittedRequestByApi(request, uniqueName('Acme Partner Lunch'), '2027-07-08T09:00:00+08:00', '2027-07-08T12:00:00+08:00')
  await assignedByApi(request, elsewhere, 'ec2')
  const unassigned = await submittedRequestByApi(request, uniqueName('Acme Unassigned Expo'), '2027-07-09T09:00:00+08:00', '2027-07-09T12:00:00+08:00')
  expect((await ask(elsewhere)).status()).toBe(403)
  expect((await ask(unassigned)).ok()).toBeFalsy()
  expect((await requestAs(request, elsewhere)).status).toBe('pending')
})

test('EC01 (conflict): TC8, TC9, TC10 while waiting it cannot be approved but can be rejected; after resubmission the same coordinator has it back and is told', async ({ browser, request }) => {
  const waiting = await assignedRequest(request, 'Acme Clarify Then Reject')
  await clarifyByApi(request, waiting.id)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${waiting.id}`)
  await expect(coordinator.getByText('Waiting for the organiser')).toBeVisible()
  await expect(coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' })).toHaveCount(0)
  const approve = await request.post(`/api/event-requests/${waiting.id}/approve`, { headers: await auth(request, 'ec1') })
  expect(approve.ok()).toBeFalsy()
  const reject = await request.post(`/api/event-requests/${waiting.id}/reject`, { headers: await auth(request, 'ec1'),
    data: { reason: 'The organiser no longer needs it' } })
  expect(reject.ok(), await reject.text()).toBeTruthy()

  const { id, name } = await assignedRequest(request, 'Acme Multi Round Seminar')
  await clarifyByApi(request, id)
  expect((await resubmitByApi(request, id, 'Confirmed: 150')).ok()).toBeTruthy()
  const resubmitted = await requestAs(request, id)
  expect(resubmitted.status).toBe('pending')
  const lead = await signedIn(browser, 'ecl1')
  await lead.goto('/coordinator-lead/assignments')
  await expect(lead.getByText(name).first()).toBeVisible()
  await coordinator.reload()
  await expect((await openBell(coordinator)).getByRole('listitem').filter({ hasText: `"${name}" was updated and resubmitted` })).toBeVisible()
})

test('EC01 (negative): TC12 only the assigned coordinator and the organisation\'s organisers can read the conversation', async ({ request }) => {
  const { id } = await assignedRequest(request, 'Acme Multi Round Seminar')
  await clarifyByApi(request, id)
  for (const username of ['eo1', 'eo2']) {
    expect((await request.get(`/api/event-requests/${id}/timeline`, { headers: await auth(request, username) })).ok(), username).toBeTruthy()
  }
  // The assigned coordinator reads it as part of the review.
  expect((await request.get(`/api/event-requests/${id}/review`, { headers: await auth(request, 'ec1') })).ok()).toBeTruthy()
  for (const username of ['ec2', 'vs1', 'eo3']) {
    expect((await request.get(`/api/event-requests/${id}/timeline`, { headers: await auth(request, username) })).ok(), username).toBeFalsy()
  }
})

test('EC01 (failure): TC13 if the clarification cannot be saved the coordinator is told and nothing changes', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Network Failure Talk')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.route(`**/api/event-requests/${id}/clarifications`, (route) => route.abort())
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('button', { name: 'Request clarification' }).click()
  await coordinator.getByLabel('What do you need from the organiser?').fill('Please confirm the numbers')
  await coordinator.getByRole('button', { name: 'Send clarification request' }).click()
  await expect(coordinator.getByRole('alert')).toContainText('Could not reach ConnectSphere')
  expect((await requestAs(request, id)).status).toBe('pending')
})

// ---------------------------------------------------------------- EC02

test('EC02 (happy): TC1 the review queue lists Submitted requests oldest first, and the ones waiting for the organiser separately', async ({ browser, request }) => {
  const older = await assignedRequest(request, 'Acme Queue Older')
  const newer = await assignedRequest(request, 'Acme Queue Newer')
  const waiting = await assignedRequest(request, 'Acme Queue Waiting')
  await clarifyByApi(request, waiting.id)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/review-queue')
  const needs = coordinator.getByRole('region', { name: /Needs your review/ })
  await expect(needs.getByText(newer.name)).toBeVisible()
  const names = await needs.getByRole('listitem').allInnerTexts()
  const at = (n: string) => names.findIndex((t) => t.includes(n))
  expect(at(older.name)).toBeGreaterThanOrEqual(0)
  expect(at(older.name)).toBeLessThan(at(newer.name))
  await expect(coordinator.getByRole('region', { name: /Waiting for the organiser/ }).getByText(waiting.name)).toBeVisible()
  await expect(needs.getByText(waiting.name)).toHaveCount(0)
})

test('EC02 (negative): TC2 a request assigned to someone else, or to nobody, cannot be opened or decided', async ({ browser, request }) => {
  const elsewhere = await submittedRequestByApi(request, uniqueName('Acme Partner Lunch'), '2027-07-10T09:00:00+08:00', '2027-07-10T12:00:00+08:00')
  await assignedByApi(request, elsewhere, 'ec2')
  const unassigned = await submittedRequestByApi(request, uniqueName('Acme Unassigned Expo'), '2027-07-11T09:00:00+08:00', '2027-07-11T12:00:00+08:00')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${elsewhere}`)
  await expect(coordinator.getByRole('heading', { name: "This request isn't assigned to you" })).toBeVisible()
  const ec = await auth(request, 'ec1')
  expect((await request.get(`/api/event-requests/${elsewhere}/review`, { headers: ec })).status()).toBe(403)
  expect((await request.post(`/api/event-requests/${elsewhere}/approve`, { headers: ec })).ok()).toBeFalsy()
  expect((await request.get(`/api/event-requests/${unassigned}/review`, { headers: ec })).ok()).toBeFalsy()
  expect((await requestAs(request, elsewhere)).status).toBe('pending')
})

test('EC02 (happy): TC3, TC4, TC5, TC6 the review shows every detail and the history; flagged fields carry into the clarification; one outcome at a time', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Investor Briefing')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${id}`)
  for (const detail of ['Event name', 'Purpose', 'Start date & time', 'End date & time', 'Expected attendance',
    'Venue requirements', 'Accessibility requirements', 'Equipment requirements', 'Registration']) {
    await expect(coordinator.getByText(detail, { exact: true }).first()).toBeVisible()
  }
  await expect(coordinator.getByText(name).first()).toBeVisible()
  await expect(coordinator.getByRole('heading', { name: 'History' })).toBeVisible()
  const outcomes = coordinator.getByRole('group', { name: 'Choose an outcome' })
  await expect(outcomes.getByRole('button', { name: 'Approve' })).toBeVisible()
  await expect(outcomes.getByRole('button', { name: /^Request clarification/ })).toBeVisible()
  await expect(outcomes.getByRole('button', { name: 'Reject' })).toBeVisible()
  await coordinator.getByRole('button', { name: 'Flag Expected attendance' }).click()
  await coordinator.getByRole('button', { name: 'Request clarification (1)' }).click()
  await expect(coordinator.getByRole('list', { name: 'Flagged fields' })).toContainText('Expected attendance')
  await expect(coordinator.getByRole('button', { name: 'Approve and start planning' })).toHaveCount(0)
})

test('EC02 (happy): TC8, TC9, TC10, TC13 approving creates an event in Planning with the same details and coordinator; venue planning reads it; the organiser is told', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Investor Briefing')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
  await coordinator.getByRole('button', { name: 'Approve and start planning' }).click()
  await expect(coordinator).toHaveURL(/\/coordinator\/events\/[0-9a-f-]{36}/)
  const eventId = coordinator.url().split('/events/')[1].split(/[/?#]/)[0]
  const event = await apiGet<{ eventName: string; expectedAttendance: number; journeyStatus: { code: string } }>(
    request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)
  expect(event).toMatchObject({ eventName: name, expectedAttendance: 150, journeyStatus: { code: 'PLANNING' } })
  expect((await requestAs(request, id)).status).toBe('approved')
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await expect(coordinator.getByText('What the event needs')).toBeVisible()
  await expect(coordinator.getByText('150', { exact: false }).first()).toBeVisible()
  await coordinator.goto(`/coordinator/requests/${id}`)
  await expect(coordinator.getByText('This request was approved and is now an event in planning.')).toBeVisible()
  const organiser = await signedIn(browser, 'eo1')
  await expect((await openBell(organiser)).getByRole('listitem').filter({ hasText: `"${name}" is now in Planning` })).toBeVisible()
})

test('EC02 (negative): TC11, TC13 rejecting needs a reason, and the organiser sees Rejected with it', async ({ browser, request }) => {
  const { id, name } = await assignedRequest(request, 'Acme Charity Run Briefing')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Reject' }).click()
  await expect(coordinator.getByRole('button', { name: 'Confirm rejection' })).toBeDisabled()
  const blank = await request.post(`/api/event-requests/${id}/reject`, { headers: await auth(request, 'ec1'), data: { reason: ' ' } })
  expect(blank.ok()).toBeFalsy()
  await coordinator.getByLabel('Reason for rejection (shown to the organiser)').fill('Not a corporate event.')
  await coordinator.getByRole('button', { name: 'Confirm rejection' }).click()
  await expect(coordinator.getByText('Request rejected. The organiser has been notified.')).toBeVisible()
  const organiser = await signedIn(browser, 'eo1')
  const card = organiser.getByRole('listitem').filter({ has: organiser.getByRole('heading', { name }) })
  await expect(card.getByText('Rejected', { exact: true }).first()).toBeVisible()
  await expect((await openBell(organiser)).getByText('Not a corporate event.').first()).toBeVisible()
})

test('EC02 (conflict): TC12 a decided request gets no second outcome, and a double approval makes one event', async ({ request }) => {
  const ec = await auth(request, 'ec1')
  const { id } = await assignedRequest(request, 'Acme Double Click Approval')
  const [first, second] = await Promise.all([
    request.post(`/api/event-requests/${id}/approve`, { headers: ec }),
    request.post(`/api/event-requests/${id}/approve`, { headers: ec }),
  ])
  expect([first.ok(), second.ok()].filter(Boolean)).toHaveLength(1)
  expect((await request.post(`/api/event-requests/${id}/reject`, { headers: ec, data: { reason: 'Too late' } })).ok()).toBeFalsy()
  const timeline = await apiGet<{ toStatus: string | null }[]>(request, await apiToken(request, 'eo1'), `/api/event-requests/${id}/timeline`)
  expect(timeline.filter((e) => e.toStatus === 'approved')).toHaveLength(1)
})

test('EC02 (failure): TC14 if the approval cannot be saved the coordinator is told and nothing changes', async ({ browser, request }) => {
  const { id } = await assignedRequest(request, 'Acme Network Failure Talk')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.route(`**/api/event-requests/${id}/approve`, (route) => route.abort())
  await coordinator.goto(`/coordinator/requests/${id}`)
  await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
  await coordinator.getByRole('button', { name: 'Approve and start planning' }).click()
  await expect(coordinator.getByRole('alert')).toBeVisible()
  const after = await requestAs(request, id)
  expect(after.status).toBe('pending')
  expect(after.eventId).toBeNull()
})

test('EC02 (negative): TC15 other roles and organisations cannot read a request, its review or its history', async ({ request }) => {
  const { id } = await assignedRequest(request, 'Acme Multi Round Seminar')
  for (const username of ['vs1', 'ts1', 'eo1']) {
    expect((await request.get(`/api/event-requests/${id}/review`, { headers: await auth(request, username) })).ok(), username).toBeFalsy()
  }
  const eo3 = await auth(request, 'eo3')
  expect((await request.get(`/api/event-requests/${id}`, { headers: eo3 })).ok()).toBeFalsy()
  expect((await request.get(`/api/event-requests/${id}/timeline`, { headers: eo3 })).ok()).toBeFalsy()
})
