// @stories EC-NEW2 EC08 EC01 EO26 SO-C1 SO-C6 VS-C1
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiGet, apiToken, approvedBookingByApi, assignedByApi, cancelledByApi, confirmByApi, createVenue, openBell,
  planningEventWithNeeds, safetyApprovedByApi, signedIn, submittedRequestByApi, uniqueDay, uniqueName, venueUnavailableByApi,
} from './support.ts'

// Sprint 3 functional test cases for EC-NEW2 (Sheet: Test Cases - Sprint 3,
// EC-NEW2-TC1…TC29): one status, named the same way for every role and on
// every screen. Not here, because the server will not create an event whose
// end has passed: TC10 (Completed) and TC11 (Date passed); and TC28 (rows
// stored before the change), which JourneyStatusResolverTest covers.

const STATUS = { badge: '.journey-badge' }
const REQUEST_STAGE = ['Draft', 'Submitted', 'Under Review', 'Clarification Required', 'Rejected', 'Cancelled']
const EVENT_STAGE = ['Planning', 'Awaiting Safety Review', 'Safety Changes Requested', 'Safety Approved', 'Confirmed', 'Completed', 'Cancelled']

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

async function code(request: APIRequestContext, eventId: string): Promise<string> {
  return (await apiGet<{ journeyStatus: { code: string } }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)).journeyStatus.code
}

function card(page: Page, name: string) {
  return page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
}

async function planning(request: APIRequestContext, prefix: string) {
  const day = uniqueDay()
  const eventName = uniqueName(prefix)
  const ids = await planningEventWithNeeds(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  return { ...ids, eventName, day }
}

test('EC-NEW2 (happy): TC1, TC2, TC3, TC4 a request is Submitted, then Under Review once assigned, Clarification Required for both sides while it waits, and Planning once approved', async ({ browser, request }) => {
  const name = uniqueName('Acme Status Journey')
  const requestId = await submittedRequestByApi(request, name, '2027-08-02T09:00:00+08:00', '2027-08-02T12:00:00+08:00')
  const organiser = await signedIn(browser, 'eo1')
  await expect(card(organiser, name).locator(STATUS.badge)).toHaveText('Submitted')
  for (const label of await organiser.locator(STATUS.badge).allInnerTexts()) {
    expect([...REQUEST_STAGE, ...EVENT_STAGE]).toContain(label.trim())
  }
  await assignedByApi(request, requestId)
  await organiser.reload()
  await expect(card(organiser, name).locator(STATUS.badge)).toHaveText('Under Review')

  const asked = await request.post(`/api/event-requests/${requestId}/clarifications`, { headers: await auth(request, 'ec1'),
    data: { message: 'Please confirm the numbers', flaggedFields: [], fieldQuestions: {} } })
  expect(asked.ok()).toBeTruthy()
  await organiser.reload()
  await expect(card(organiser, name).locator(STATUS.badge)).toHaveText('Clarification Required')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/review-queue')
  await expect(card(coordinator, name).locator(STATUS.badge)).toHaveText('Clarification Required')

  const details = await apiGet<Record<string, unknown>>(request, await apiToken(request, 'eo1'), `/api/event-requests/${requestId}`)
  expect((await request.post(`/api/event-requests/${requestId}/resubmit`, { headers: await auth(request, 'eo1'),
    data: { response: 'Confirmed: 150', details } })).ok()).toBeTruthy()
  expect((await request.post(`/api/event-requests/${requestId}/approve`, { headers: await auth(request, 'ec1') })).ok()).toBeTruthy()
  await organiser.reload()
  await expect(card(organiser, name).locator(STATUS.badge)).toHaveText('Planning')
})

test('EC-NEW2 (happy): TC5, TC6, TC7, TC8, TC9, TC20 an event moves Planning → Awaiting Safety Review → Safety Changes Requested → Safety Approved → Confirmed, each shown at once to whoever acted', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Status Event')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Status Hall')))
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/my-events')
  for (const label of await coordinator.locator(STATUS.badge).allInnerTexts()) expect(EVENT_STAGE).toContain(label.trim())

  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Awaiting Safety Review')
  await expect(coordinator.getByRole('list', { name: 'Progress: Awaiting Safety Review' })).toBeVisible()

  const so = await auth(request, 'so1')
  const items = await apiGet<{ items: { itemKey: string }[] }>(request, so.Authorization.slice(7), `/api/safety/events/${e.eventId}/readiness`)
  expect((await request.post(`/api/safety/events/${e.eventId}/approval`, { headers: so,
    data: { decision: 'request_changes', note: 'Add exit signage.', flaggedItems: [items.items[0].itemKey] } })).ok()).toBeTruthy()
  await coordinator.reload()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Safety Changes Requested')
  await expect(coordinator.getByText('Add exit signage.').first()).toBeVisible()

  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Awaiting Safety Review')
  for (const item of items.items) {
    expect((await request.patch(`/api/safety/events/${e.eventId}/readiness/items/${item.itemKey}`, { headers: so, data: { checked: true } })).ok()).toBeTruthy()
  }
  expect((await request.post(`/api/safety/events/${e.eventId}/approval`, { headers: so, data: { decision: 'approve' } })).ok()).toBeTruthy()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Awaiting Safety Review')
  await coordinator.reload()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Safety Approved')
  await coordinator.getByRole('button', { name: 'Confirm event' }).click()
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Confirmed')
})

test('EC-NEW2 (conflict): TC12, TC13 approving a booking changes no status; a confirmed event whose venue becomes unavailable goes back to Planning, with the reason logged and the coordinator told', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Riverside Concert')
  const venueId = await createVenue(request, uniqueName('Acme Riverside Hall'))
  await approvedBookingByApi(request, e.eventId, venueId)
  expect(await code(request, e.eventId)).toBe('PLANNING')
  await safetyApprovedByApi(request, e.eventId)
  await confirmByApi(request, e.eventId)
  await venueUnavailableByApi(request, venueId, `${e.day}T08:00:00+08:00`, `${e.day}T13:00:00+08:00`, 'Flooding')
  expect(await code(request, e.eventId)).toBe('PLANNING')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(coordinator.locator(STATUS.badge).first()).toHaveText('Planning')
  await expect(coordinator.getByRole('region', { name: 'Change log' })).toContainText('Flooding')
  await expect((await openBell(coordinator)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is back in Planning` })).toBeVisible()
})

test('EC-NEW2 (failure): TC14 a refused action changes neither the status nor the history', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Refused Actions')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  const entries = coordinator.getByRole('region', { name: 'Change log' }).getByRole('listitem')
  await expect(entries.first()).toBeVisible()
  const before = await entries.count()
  const ec = await auth(request, 'ec1')
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: ec })).ok()).toBeFalsy()
  expect((await request.post(`/api/events/${e.eventId}/confirm`, { headers: ec })).ok()).toBeFalsy()
  await coordinator.reload()
  await expect(entries.first()).toBeVisible()
  expect(await entries.count()).toBe(before)
  expect(await code(request, e.eventId)).toBe('PLANNING')
})

test('EC-NEW2 (cross-cutting): TC15, TC18, TC19, TC26 Safety Approved reads the same, as words, with the same explanation and next step, for the organiser, coordinator and Safety Officer, on screen and in the API', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Ready To Confirm Night')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Ready Hall')))
  await safetyApprovedByApi(request, e.eventId)
  const views: { page: Page; path: string; scope?: string }[] = [
    { page: await signedIn(browser, 'eo1'), path: '/organiser', scope: e.eventName },
    { page: await signedIn(browser, 'eo1'), path: `/organiser/events/${e.eventId}` },
    { page: await signedIn(browser, 'ec1'), path: '/coordinator/my-events', scope: e.eventName },
    { page: await signedIn(browser, 'ec1'), path: `/coordinator/events/${e.eventId}` },
    { page: await signedIn(browser, 'so1'), path: '/safety-officer', scope: e.eventName },
  ]
  const explanations = new Set<string>()
  for (const { page, path, scope } of views) {
    await page.goto(path)
    const badge = (scope ? card(page, scope) : page).locator(STATUS.badge).first()
    await expect(badge, path).toHaveText('Safety Approved')
    explanations.add((await badge.getAttribute('title')) ?? '')
  }
  expect(explanations.size).toBe(1)
  const eventPage = views[3].page
  await expect(eventPage.locator('.journey-explainer__next').first()).toContainText('Next:')

  const fromOrganiser = await apiGet<{ journeyStatus: unknown }>(request, await apiToken(request, 'eo1'), `/api/events/${e.eventId}/arrangements`)
  const fromCoordinator = await apiGet<{ journeyStatus: unknown }>(request, await apiToken(request, 'ec1'), `/api/events/${e.eventId}`)
  const fromOfficer = await apiGet<{ journeyStatus: unknown }>(request, await apiToken(request, 'so1'), `/api/safety/events/${e.eventId}`)
  expect(fromOrganiser.journeyStatus).toEqual(fromCoordinator.journeyStatus)
  expect(fromOfficer.journeyStatus).toEqual(fromCoordinator.journeyStatus)
})

test('EC-NEW2 (cross-cutting): TC16, TC17 the history and the notifications use the same status names (Planning, not Approved)', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Vocabulary Fair')
  const organiser = await signedIn(browser, 'eo1')
  await expect((await openBell(organiser)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is now in Planning` })).toBeVisible()
  await expect((await openBell(organiser)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is now Approved` })).toHaveCount(0)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  const log = coordinator.getByRole('region', { name: 'Change log' })
  await expect(log).toContainText('Planning')
  await expect(log).not.toContainText('→ Approved')
})

test('EC-NEW2 (conflict): TC21 acting on a status that has since changed is refused with the current status, and nothing changes', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Stale Withdrawal')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Stale Hall')))
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: await auth(request, 'ec1') })).ok()).toBeTruthy()
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await coordinator.getByRole('button', { name: 'Withdraw from review' }).click()
  const so = await auth(request, 'so1')
  const items = await apiGet<{ items: { itemKey: string }[] }>(request, so.Authorization.slice(7), `/api/safety/events/${e.eventId}/readiness`)
  expect((await request.post(`/api/safety/events/${e.eventId}/approval`, { headers: so,
    data: { decision: 'request_changes', note: 'Fix the exits.', flaggedItems: [items.items[0].itemKey] } })).ok()).toBeTruthy()
  await coordinator.getByLabel('Why are you withdrawing it?').fill('The organiser changed plans.')
  await coordinator.getByRole('button', { name: 'Withdraw', exact: true }).click()
  await expect(coordinator.locator('.readiness__error')).toContainText(/Safety Changes Requested|changes/i)
  expect(await code(request, e.eventId)).toBe('SAFETY_CHANGES_REQUESTED')
})

test('EC-NEW2 (negative): TC22, TC23 nobody is offered an action their role cannot take now, and the API refuses it too', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Product Launch')
  const officer = await signedIn(browser, 'so1')
  await officer.goto(`/safety-officer/events/${e.eventId}`)
  await expect(officer.getByRole('button', { name: /Approve|Request changes|Reject/ })).toHaveCount(0)
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${e.eventId}`)
  await expect(organiser.getByRole('button', { name: /Send for safety review|Confirm event|Withdraw from review/ })).toHaveCount(0)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(coordinator.getByRole('button', { name: /Send for safety review|Confirm event/ })).toHaveCount(0)

  for (const [username, path] of [['eo1', 'confirm'], ['eo1', 'safety-review'], ['so1', 'confirm'], ['ecl1', 'safety-review']]) {
    expect((await request.post(`/api/events/${e.eventId}/${path}`, { headers: await auth(request, username) })).ok(), `${username} ${path}`).toBeFalsy()
  }
  expect((await request.post(`/api/safety/events/${e.eventId}/approval`, { headers: await auth(request, 'so1'),
    data: { decision: 'approve' } })).ok()).toBeFalsy()
  expect(await code(request, e.eventId)).toBe('PLANNING')
})

test('EC-NEW2 (happy): TC24, TC25 lists can be filtered by status, with a count on each chip', async ({ browser, request }) => {
  await planning(request, 'Acme Filter Planning')
  const organiser = await signedIn(browser, 'eo1')
  const chips = organiser.getByRole('group', { name: 'Filter your requests by status' })
  await chips.getByRole('button', { name: /^Planning \d+$/ }).click()
  const badges = await organiser.locator(STATUS.badge).allInnerTexts()
  expect(badges.length).toBeGreaterThan(0)
  expect(new Set(badges.map((b) => b.trim()))).toEqual(new Set(['Planning']))

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/my-events')
  await expect(coordinator.getByRole('group', { name: 'Filter my events by status' }).getByRole('button', { name: /^All \d+$/ })).toBeVisible()
  await coordinator.goto('/coordinator/review-queue')
  await expect(coordinator.getByRole('group', { name: 'Filter the review queue by status' })).toBeVisible()
  const lead = await signedIn(browser, 'ecl1')
  await lead.goto('/coordinator-lead/assignments')
  await expect(lead.getByRole('group', { name: 'Filter assigned requests by status' })).toBeVisible()
  // Unassigned requests are all Submitted, so that list shows its filter only once
  // a second status appears there (StatusFilter hides a single-choice filter).
})

test('EC-NEW2 (boundary): TC27 every event in the coordinator\'s list has a status', async ({ request }) => {
  const events = await apiGet<{ journeyStatus: { code: string } | null }[]>(request, await apiToken(request, 'ec1'), '/api/events/assigned')
  expect(events.length).toBeGreaterThan(0)
  expect(events.filter((ev) => !ev.journeyStatus?.code)).toHaveLength(0)
})

test('EC-NEW2 (negative): TC29 rejected and cancelled items offer no further actions, and attempts are refused naming the current status', async ({ browser, request }) => {
  const rejectedName = uniqueName('Acme Rejected Already')
  const rejectedId = await submittedRequestByApi(request, rejectedName, '2027-08-04T09:00:00+08:00', '2027-08-04T12:00:00+08:00')
  await assignedByApi(request, rejectedId)
  expect((await request.post(`/api/event-requests/${rejectedId}/reject`, { headers: await auth(request, 'ec1'),
    data: { reason: 'Not a corporate event.' } })).ok()).toBeTruthy()
  const cancelled = await planning(request, 'Acme Cancelled Mixer')
  await cancelledByApi(request, cancelled.eventId, cancelled.requestId)

  const organiser = await signedIn(browser, 'eo1')
  await expect(card(organiser, rejectedName).getByRole('link', { name: /Continue editing|Respond|View event|Request a change/ })).toHaveCount(0)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${cancelled.eventId}`)
  await expect(coordinator.getByRole('button', { name: /Send for safety review|Confirm event|Withdraw from review/ })).toHaveCount(0)

  const submit = await request.post(`/api/event-requests/${rejectedId}/submit`, { headers: await auth(request, 'eo1') })
  expect(submit.ok()).toBeFalsy()
  expect(await submit.text()).toMatch(/rejected/i)
  const approve = await request.post(`/api/event-requests/${rejectedId}/approve`, { headers: await auth(request, 'ec1') })
  expect(approve.ok()).toBeFalsy()
  expect(await approve.text()).toMatch(/rejected/i)
  const send = await request.post(`/api/events/${cancelled.eventId}/safety-review`, { headers: await auth(request, 'ec1') })
  expect(send.ok()).toBeFalsy()
  expect(await send.text()).toMatch(/cancel/i)
})
