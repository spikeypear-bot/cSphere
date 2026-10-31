// @stories EC08 EC-NEW2 SO-C1 SO-C6 EC-C2 VS-C3 VS-C1
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiGet, apiToken, approvedBookingByApi, approvedEquipmentByApi, cancelledByApi, confirmByApi, createVenue, openBell,
  planningEventWithNeeds, safetyApprovedByApi, signedIn, uniqueDay, uniqueName, venueUnavailableByApi,
} from './support.ts'

// Sprint 3 functional test cases for EC08 (Sheet: Test Cases - Sprint 3,
// EC08-TC1…TC36): readiness, sending for the safety check, withdrawing and
// confirming. TC9, TC10 and TC22 were Blocked in Sprint 3 until EC-C2, VS-C3
// and booking cancellation existed; they run here. Not here: TC30 (an event
// whose end has passed, which the server will not create; backend tests with
// a clock) and TC36 (the regression suites themselves, which CI runs).

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

async function status(request: APIRequestContext, eventId: string): Promise<string> {
  return (await apiGet<{ journeyStatus: { code: string } }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)).journeyStatus.code
}

function readinessItem(page: Page, label: string) {
  return page.locator('.readiness__item').filter({ has: page.locator('.readiness__label', { hasText: label }) })
}

async function planning(request: APIRequestContext, prefix: string, needs: Parameters<typeof planningEventWithNeeds>[4] = {}) {
  const day = uniqueDay()
  const eventName = uniqueName(prefix)
  const { eventId, requestId } = await planningEventWithNeeds(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`, needs)
  return { eventId, requestId, eventName, day }
}

test('EC08 (negative): TC1, TC2, TC3 the checklist shows each prerequisite; with no venue the event cannot be sent, by page or API; Resolve leads to the venue booking', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Product Launch')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(coordinator.getByText('Ready for confirmation?')).toBeVisible()
  for (const label of ['Venue bookings', 'Equipment', 'Operational Safety Check']) await expect(readinessItem(coordinator, label)).toBeVisible()
  await expect(readinessItem(coordinator, 'Venue bookings').locator('.readiness__state')).toHaveText('To do')
  await expect(coordinator.getByText('Not ready yet')).toBeVisible()
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toHaveCount(0)
  const refused = await request.post(`/api/events/${e.eventId}/safety-review`, { headers: await auth(request, 'ec1') })
  expect(refused.status()).toBe(422)
  expect(await status(request, e.eventId)).toBe('PLANNING')
  await readinessItem(coordinator, 'Venue bookings').getByRole('link', { name: 'Resolve' }).click()
  await expect(coordinator).toHaveURL(new RegExp(`/coordinator/events/${e.eventId}/venue-booking$`))
})

test('EC08 (conflict): TC4, TC11 a pending booking blocks sending; once Venue Staff approve, a reload shows it done and offers Send', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Leadership Breakfast')
  const venueId = await createVenue(request, uniqueName('Acme Breakfast Hall'))
  const booked = await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId } })
  expect(booked.ok()).toBeTruthy()
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(readinessItem(coordinator, 'Venue bookings')).toContainText('1 booking is still waiting for Venue Staff.')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toHaveCount(0)
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: await auth(request, 'ec1') })).status()).toBe(422)
  expect((await request.patch(`/api/venue-bookings/${(await booked.json()).bookingId}/approve`, { headers: await auth(request, 'vs1') })).ok()).toBeTruthy()
  await coordinator.reload()
  await expect(readinessItem(coordinator, 'Venue bookings').locator('.readiness__state')).toHaveText('Done')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toBeVisible()
})

test('EC08 (negative): TC5, TC6, TC7 equipment the organiser listed must be requested and approved; with none listed it is not needed', async ({ browser, request }) => {
  const venueId = await createVenue(request, uniqueName('Acme Equipment Hall'))
  const needsIt = await planning(request, 'Acme Equipment Night', { equipmentRequirements: 'Two projection screens' })
  await approvedBookingByApi(request, needsIt.eventId, venueId)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${needsIt.eventId}`)
  await expect(readinessItem(coordinator, 'Equipment')).toContainText('The organiser listed equipment requirements, but no equipment has been requested.')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toHaveCount(0)

  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, await apiToken(request, 'ec1'), '/api/equipment/catalogue')
  const screen = catalogue.find((i) => i.equipmentName === 'Projection Screen')!
  const asked = await request.post(`/api/events/${needsIt.eventId}/equipment-request`, { headers: await auth(request, 'ec1'),
    data: { technicalRequirement: 'Two screens.', items: [{ equipmentId: screen.equipmentId, quantity: 2 }] } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  await coordinator.reload()
  await expect(readinessItem(coordinator, 'Equipment')).toContainText('1 equipment request is still waiting for Technical Support.')
  expect((await request.post(`/api/events/${needsIt.eventId}/safety-review`, { headers: await auth(request, 'ec1') })).status()).toBe(422)

  const none = await planning(request, 'Acme Plain Talk')
  await approvedBookingByApi(request, none.eventId, await createVenue(request, uniqueName('Acme Talk Room')))
  await coordinator.goto(`/coordinator/events/${none.eventId}`)
  await expect(readinessItem(coordinator, 'Equipment').locator('.readiness__state')).toHaveText('Not needed')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toBeVisible()
})

test('EC08 (boundary): TC8, TC9, TC10 a rejected-then-replaced booking does not block; a second pending venue (EC-C2) or a tentative hold (VS-C3) does', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Replacement Venue Conference')
  const ec = await auth(request, 'ec1')
  const vs = await auth(request, 'vs1')
  const firstId = await createVenue(request, uniqueName('Acme First Hall'))
  const first = await (await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: ec, data: { venueId: firstId } })).json()
  expect((await request.patch(`/api/venue-bookings/${first.bookingId}/reject`, { headers: vs,
    data: { reason: 'Closed for works', alternativeVenueId: null, alternativeArrangement: null } })).ok()).toBeTruthy()
  const replacement = await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: ec,
    data: { venueId: await createVenue(request, uniqueName('Acme Second Hall')), replacesBookingId: first.bookingId } })
  expect(replacement.ok(), await replacement.text()).toBeTruthy()
  expect((await request.patch(`/api/venue-bookings/${(await replacement.json()).bookingId}/approve`, { headers: vs })).ok()).toBeTruthy()
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(readinessItem(coordinator, 'Venue bookings').locator('.readiness__state')).toHaveText('Done')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toBeVisible()

  const extra = await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: ec,
    data: { venueId: await createVenue(request, uniqueName('Jurong Innovation Hall')) } })
  expect(extra.ok(), await extra.text()).toBeTruthy()
  await coordinator.reload()
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toHaveCount(0)
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: ec })).status()).toBe(422)

  const held = await planning(request, 'Acme Held Venue Talk')
  const booking = await (await request.post(`/api/events/${held.eventId}/venue-bookings`, { headers: ec,
    data: { venueId: await createVenue(request, uniqueName('Acme Held Hall')) } })).json()
  const hold = await request.post(`/api/venue-bookings/${booking.bookingId}/holds`, { headers: vs,
    data: { expiresAt: new Date(Date.now() + 72 * 3_600_000).toISOString() } })
  expect(hold.ok(), await hold.text()).toBeTruthy()
  expect((await request.post(`/api/events/${held.eventId}/safety-review`, { headers: ec })).status()).toBe(422)
})

test('EC08 (happy): TC12, TC13, TC14, TC15, TC16, TC17 the coordinator sends the event, it waits for the Safety Officer, and is withdrawn with a reason of 1 to 2,000 characters', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Leadership Breakfast')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Breakfast Hall')))
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.getByText('Sent for the Operational Safety Check. You will be able to confirm once it is approved.')).toBeVisible()
  await expect(coordinator.getByText('Awaiting Safety Review').first()).toBeVisible()
  await expect(coordinator.getByRole('button', { name: 'Withdraw from review' })).toBeVisible()
  await coordinator.reload()
  await expect(coordinator.getByRole('region', { name: 'Change log' })).toContainText('ec1 (Event Coordinator)')

  const officer = await signedIn(browser, 'so1')
  await officer.goto('/safety-officer')
  await expect(officer.getByRole('region', { name: 'Waiting for your safety review' }).getByText(e.eventName)).toBeVisible()

  await coordinator.getByRole('button', { name: 'Withdraw from review' }).click()
  const withdraw = coordinator.getByRole('button', { name: 'Withdraw', exact: true })
  await expect(withdraw).toBeDisabled()
  await coordinator.getByLabel('Why are you withdrawing it?').fill('    ')
  await expect(withdraw).toBeDisabled()
  const ec = await auth(request, 'ec1')
  for (const reason of ['', 'x'.repeat(2001)]) {
    expect((await request.post(`/api/events/${e.eventId}/safety-review/withdraw`, { headers: ec, data: { reason } })).ok(), `${reason.length}`).toBeFalsy()
  }
  expect(await status(request, e.eventId)).toBe('AWAITING_SAFETY_REVIEW')
  await coordinator.getByLabel('Why are you withdrawing it?').fill('The organiser changed the expected attendance.')
  await withdraw.click()
  await expect(coordinator.getByText('Withdrawn from safety review. The event is back in Planning.')).toBeVisible()
  expect(await status(request, e.eventId)).toBe('PLANNING')
  await coordinator.reload()
  await expect(coordinator.getByRole('region', { name: 'Change log' })).toContainText('The organiser changed the expected attendance.')
  await officer.reload()
  await expect(officer.getByRole('region', { name: 'Waiting for your safety review' }).getByText(e.eventName)).toHaveCount(0)
})

test('EC08 (happy): TC18, TC19 when the Safety Officer requests changes, the coordinator sees the feedback and can send it again', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Safety Feedback Forum')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Feedback Hall')))
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: await auth(request, 'ec1') })).ok()).toBeTruthy()
  const so = await auth(request, 'so1')
  const items = await apiGet<{ items: { itemKey: string }[] }>(request, so.Authorization.slice(7), `/api/safety/events/${e.eventId}/readiness`)
  const asked = await request.post(`/api/safety/events/${e.eventId}/approval`, { headers: so,
    data: { decision: 'request_changes', note: 'Add a second fire marshal.', flaggedItems: [items.items[0].itemKey] } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  expect(await status(request, e.eventId)).toBe('SAFETY_CHANGES_REQUESTED')
  const coordinator = await signedIn(browser, 'ec1')
  await expect((await openBell(coordinator)).getByRole('listitem').filter({ hasText: e.eventName }).filter({ hasText: 'Add a second fire marshal.' }).first()).toBeVisible()
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(coordinator.getByText("Safety Officer's feedback").first()).toBeVisible()
  await expect(coordinator.getByText('Add a second fire marshal.').first()).toBeVisible()
  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.getByText('Awaiting Safety Review').first()).toBeVisible()
})

test('EC08 (conflict): TC20, TC21, TC22 a venue becoming unavailable, or a cancelled booking, returns the event to Planning and tells the coordinator why', async ({ browser, request }) => {
  const awaiting = await planning(request, 'Acme Awaiting Review Gala')
  const awaitingVenue = await createVenue(request, uniqueName('Acme Awaiting Hall'))
  await approvedBookingByApi(request, awaiting.eventId, awaitingVenue)
  expect((await request.post(`/api/events/${awaiting.eventId}/safety-review`, { headers: await auth(request, 'ec1') })).ok()).toBeTruthy()
  await venueUnavailableByApi(request, awaitingVenue, `${awaiting.day}T08:00:00+08:00`, `${awaiting.day}T13:00:00+08:00`, 'Burst pipe')
  expect(await status(request, awaiting.eventId)).toBe('PLANNING')

  const approved = await planning(request, 'Acme Ready To Confirm Night')
  const approvedVenue = await createVenue(request, uniqueName('Acme Approved Hall'))
  await approvedBookingByApi(request, approved.eventId, approvedVenue)
  await safetyApprovedByApi(request, approved.eventId)
  await venueUnavailableByApi(request, approvedVenue, `${approved.day}T08:00:00+08:00`, `${approved.day}T13:00:00+08:00`, 'Roof repairs')
  expect(await status(request, approved.eventId)).toBe('PLANNING')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${approved.eventId}`)
  await expect(coordinator.getByRole('button', { name: 'Confirm event' })).toHaveCount(0)
  await expect((await openBell(coordinator)).getByRole('listitem').filter({ hasText: `"${awaiting.eventName}" is back in Planning` })).toBeVisible()

  const cancelled = await planning(request, 'Acme Cancelled Booking Night')
  const bookingId = await approvedBookingByApi(request, cancelled.eventId, await createVenue(request, uniqueName('Acme Cancel Hall')))
  await safetyApprovedByApi(request, cancelled.eventId)
  const cancel = await request.post(`/api/events/${cancelled.eventId}/venue-bookings/${bookingId}/cancel`, {
    headers: await auth(request, 'ec1'), data: { reason: 'Moving to a bigger room' } })
  expect(cancel.ok(), await cancel.text()).toBeTruthy()
  expect(await status(request, cancelled.eventId)).toBe('PLANNING')
})

test('EC08 (happy): TC23, TC24, TC25, TC26, TC27, TC28 only a Safety Approved event can be confirmed; confirming tells the organiser and the staff involved and changes no arrangement', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Annual Conference', { equipmentRequirements: 'Two projection screens' })
  const bookingId = await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Conference Hall')))
  await approvedEquipmentByApi(request, e.eventId, `${e.day}T01:00:00Z`, `${e.day}T04:00:00Z`)
  const ec = await auth(request, 'ec1')
  expect((await request.post(`/api/events/${e.eventId}/confirm`, { headers: ec })).ok()).toBeFalsy()
  await safetyApprovedByApi(request, e.eventId)

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await coordinator.getByRole('button', { name: 'Confirm event' }).click()
  await expect(coordinator.getByText('Event confirmed. The organiser and the staff involved have been notified.')).toBeVisible()
  await expect(coordinator.getByRole('button', { name: /Send for safety review|Confirm event|Withdraw from review/ })).toHaveCount(0)
  await coordinator.reload()
  await expect(coordinator.getByRole('region', { name: 'Change log' })).toContainText('ec1 (Event Coordinator) confirmed the event')

  const organiser = await signedIn(browser, 'eo1')
  await expect((await openBell(organiser)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is now Confirmed` })).toBeVisible()
  for (const staff of ['vs1', 'ts1']) {
    const page = await signedIn(browser, staff)
    await expect((await openBell(page)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is confirmed` }), staff).toBeVisible()
  }
  const bookings = await apiGet<{ bookingId: string; status: string }[]>(request, ec.Authorization.slice(7), `/api/events/${e.eventId}/venue-bookings`)
  expect(bookings.find((b) => b.bookingId === bookingId)?.status).toBe('approved')
  const equipment = await apiGet<{ status: string }[]>(request, ec.Authorization.slice(7), `/api/events/${e.eventId}/equipment-requests`)
  expect(equipment.map((r) => r.status)).toEqual(['approved'])

  const noEquipment = await planning(request, 'Acme Beach Picnic')
  await approvedBookingByApi(request, noEquipment.eventId, await createVenue(request, uniqueName('Acme Beach Hall')))
  await safetyApprovedByApi(request, noEquipment.eventId)
  await confirmByApi(request, noEquipment.eventId)
  const technician = await signedIn(browser, 'ts1')
  await expect((await openBell(technician)).getByRole('listitem').filter({ hasText: `"${noEquipment.eventName}" is confirmed` })).toHaveCount(0)
})

test('EC08 (conflict): TC29 if a prerequisite stops holding before Confirm is pressed, it is refused and the coordinator is told', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Last Minute Night')
  const venueId = await createVenue(request, uniqueName('Acme Last Minute Hall'))
  await approvedBookingByApi(request, e.eventId, venueId)
  await safetyApprovedByApi(request, e.eventId)
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await expect(coordinator.getByRole('button', { name: 'Confirm event' })).toBeVisible()
  await venueUnavailableByApi(request, venueId, `${e.day}T08:00:00+08:00`, `${e.day}T13:00:00+08:00`, 'Power outage')
  await coordinator.getByRole('button', { name: 'Confirm event' }).click()
  await expect(coordinator.locator('.readiness__error')).toBeVisible()
  expect(await status(request, e.eventId)).not.toBe('CONFIRMED')
})

test('EC08 (negative): TC31, TC32, TC33 finished events, other coordinators and other roles are refused, and nothing changes', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Finished Conference')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Finished Hall')))
  await safetyApprovedByApi(request, e.eventId)
  await confirmByApi(request, e.eventId)
  const ec = await auth(request, 'ec1')
  const confirmAgain = await request.post(`/api/events/${e.eventId}/confirm`, { headers: ec })
  expect(confirmAgain.status()).toBe(409)
  expect(await confirmAgain.text()).toMatch(/confirmed/i)
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: ec })).ok()).toBeFalsy()

  const cancelled = await planning(request, 'Acme Cancelled Mixer')
  await cancelledByApi(request, cancelled.eventId, cancelled.requestId)
  const send = await request.post(`/api/events/${cancelled.eventId}/safety-review`, { headers: ec })
  expect(send.ok()).toBeFalsy()
  expect(await send.text()).toMatch(/cancel/i)

  const ec2 = await auth(request, 'ec2')
  for (const path of ['confirm', 'safety-review']) {
    expect((await request.post(`/api/events/${e.eventId}/${path}`, { headers: ec2 })).status(), path).toBe(403)
  }
  const other = await signedIn(browser, 'ec2')
  await other.goto(`/coordinator/events/${e.eventId}`)
  await expect(other.getByRole('heading', { name: "This event isn't assigned to you" })).toBeVisible()
  for (const username of ['eo1', 'vs1', 'ts1', 'so1', 'ecl1']) {
    expect((await request.post(`/api/events/${e.eventId}/confirm`, { headers: await auth(request, username) })).status(), username).toBe(403)
  }
  expect(await status(request, e.eventId)).toBe('CONFIRMED')
})

test('EC08 (conflict): TC34 two Confirm presses at the same moment confirm once and tell the organiser once', async ({ request }) => {
  const e = await planning(request, 'Acme Double Confirm Night')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Double Hall')))
  await safetyApprovedByApi(request, e.eventId)
  const ec = await auth(request, 'ec1')
  const results = await Promise.all([1, 2].map(() => request.post(`/api/events/${e.eventId}/confirm`, { headers: ec })))
  expect(results.filter((r) => r.ok())).toHaveLength(1)
  const notices = await apiGet<{ eventName: string | null; newStatus: string | null }[]>(request, await apiToken(request, 'eo1'), '/api/notifications')
  expect(notices.filter((n) => n.eventName === e.eventName && /confirm/i.test(n.newStatus ?? ''))).toHaveLength(1)
})

test('EC08 (failure): TC35 if sending cannot be saved the coordinator is told and the event stays in Planning', async ({ browser, request }) => {
  const e = await planning(request, 'Acme Network Failure Talk')
  await approvedBookingByApi(request, e.eventId, await createVenue(request, uniqueName('Acme Offline Hall')))
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.route(`**/api/events/${e.eventId}/safety-review`, (route) => route.abort())
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.locator('.readiness__error')).toBeVisible()
  await coordinator.unroute(`**/api/events/${e.eventId}/safety-review`)
  await coordinator.reload()
  expect(await status(request, e.eventId)).toBe('PLANNING')
  await expect(coordinator.getByRole('button', { name: 'Send for safety review' })).toBeVisible()
})
