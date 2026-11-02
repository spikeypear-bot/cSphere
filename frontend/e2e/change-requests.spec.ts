// @stories EO03 EC-NEW3 EC-NEW5
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, createVenue, leadAssigns, openBell, planningEventByApi, requestIdOf, signedIn, submitRequest,
  uniqueDay, uniqueName } from './support.ts'

// EO03 + EC-NEW3 through the browser and the packaged backend: an organiser
// asks to change a booked event; the coordinator sees what it would affect,
// must acknowledge it, and approves; the organiser sees the new details.
// Expected values come from the ACs, not from what the page happened to show.

test('EO03 EC-NEW3 EC-NEW5 (happy): an organiser asks for a change and the coordinator approves it after acknowledging its impact', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('E2E Change Gala')
  const venueName = uniqueName('E2E Change Hall')
  const venueId = await createVenue(request, venueName)
  const organiser = await signedIn(browser, 'eo1')
  await submitRequest(organiser, eventName, '2027-09-01T09:00', '2027-09-01T12:00')
  const requestId = await requestIdOf(request, eventName)
  await leadAssigns(await signedIn(browser, 'ecl1'), requestId)

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${requestId}`)
  await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
  await coordinator.getByRole('button', { name: 'Approve and start planning' }).click()
  await expect(coordinator).toHaveURL(/\/coordinator\/events\/[0-9a-f-]{36}/)
  const eventId = coordinator.url().split('/events/')[1].split(/[/?#]/)[0]
  // Fixture: the venue is booked and approved for 09:00-12:00 (not what this journey tests).
  const ec = await apiToken(request, 'ec1')
  const booked = await request.post(`/api/events/${eventId}/venue-bookings`, {
    headers: { Authorization: `Bearer ${ec}` }, data: { venueId } })
  expect(booked.ok()).toBeTruthy()
  const { bookingId } = await booked.json() as { bookingId: string }
  const vs = await apiToken(request, 'vs1')
  expect((await request.patch(`/api/venue-bookings/${bookingId}/approve`, { headers: { Authorization: `Bearer ${vs}` } })).ok()).toBeTruthy()

  await test.step('EO03 AC8, AC22, AC23, AC25: the organiser asks for 180 people and an hour longer, reviewing previous → new', async () => {
    await organiser.goto(`/organiser/events/${eventId}`)
    await organiser.getByRole('link', { name: 'Request a change' }).click()
    await expect(organiser.getByLabel('Expected attendance')).toHaveValue('150')
    await organiser.getByLabel('Expected attendance').fill('180')
    await organiser.getByLabel('End date & time').fill('2027-09-01T13:00')
    await organiser.getByRole('button', { name: 'Review the change' }).click()
    await expect(organiser.getByRole('row', { name: /Expected attendance/ })).toContainText('180')
    await expect(organiser.getByRole('row', { name: /End/ })).toContainText(/0?1:00 pm/i)
    await expect(organiser.getByText(/may mean your coordinator has to rearrange/)).toBeVisible()
    await organiser.getByLabel('Why is this change needed?').fill('More teams are joining')
    await organiser.getByRole('button', { name: 'Send change request' }).click()
    await expect(organiser.getByText(/Change request sent/)).toBeVisible()
  })

  await test.step('EO03 AC28, AC29, AC30: nothing changes yet, and the event shows the change waiting', async () => {
    await organiser.goto(`/organiser/events/${eventId}`)
    await expect(organiser.getByRole('region', { name: 'Change requested' })).toBeVisible()
    const token = await apiToken(request, 'eo1')
    expect((await apiGet<{ expectedAttendance: number }>(request, token, `/api/events/${eventId}`)).expectedAttendance).toBe(150)
  })

  await test.step('EC-NEW3 AC3, AC6, AC8, AC26, AC32: the coordinator sees the venue is booked for the old time and must acknowledge it to approve', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await coordinator.getByRole('link', { name: 'Review and decide' }).click()
    await expect(coordinator.getByText(`${venueName} is booked for the old time`)).toBeVisible()
    const approve = coordinator.getByRole('button', { name: 'Approve and apply the change' })
    await expect(approve).toBeDisabled()
    for (const box of await coordinator.getByRole('checkbox', { name: 'I have considered this' }).all()) await box.check()
    await approve.click()
    await expect(coordinator.getByText(/Change approved and applied/)).toBeVisible()
    await expect(coordinator.getByText('Still to follow up')).toBeVisible()
  })

  await test.step('EO03 AC38, AC39, EC-NEW5: the organiser sees the new details, the decision, and the change log', async () => {
    const token = await apiToken(request, 'eo1')
    const event = await apiGet<{ expectedAttendance: number }>(request, token, `/api/events/${eventId}`)
    expect(event.expectedAttendance).toBe(180)
    await organiser.goto(`/organiser/requests/${requestId}/changes`)
    await expect(organiser.getByText('Approved by ec1')).toBeVisible()
    await organiser.goto(`/organiser/events/${eventId}`)
    await expect(organiser.getByRole('region', { name: 'Change log' })).toContainText('approved a change to the event')
  })
})

async function headers(request: APIRequestContext, username: string) {
  return { Authorization: `Bearer ${await apiToken(request, username)}` }
}

/** Test data: eo1 asks, through the API, for the given details to change. Returns the change request's id. */
async function askForChange(request: APIRequestContext, requestId: string, changes: Record<string, unknown>, reason: string) {
  const current = await apiGet<Record<string, unknown>>(request, await apiToken(request, 'eo1'), `/api/event-requests/${requestId}`)
  const asked = await request.post('/api/change-requests', { headers: await headers(request, 'eo1'),
    data: { requestId, reason, details: { ...current, ...changes } } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  return (await asked.json()).changeRequestId as string
}

/** Test data: ec1 approves a change through the API, acknowledging every item that needs attention. */
async function approveChange(request: APIRequestContext, changeId: string) {
  const review = await apiGet<{ impact: { key: string; status: string }[] }>(request, await apiToken(request, 'ec1'), `/api/change-requests/${changeId}`)
  const approved = await request.post(`/api/change-requests/${changeId}/approve`, { headers: await headers(request, 'ec1'),
    data: { acknowledged: review.impact.filter((i) => i.status === 'attention').map((i) => i.key) } })
  expect(approved.ok(), await approved.text()).toBeTruthy()
}

/** Test data: an event of eo1's in Planning on `day`, 09:00-12:00 Singapore time, with an approved equipment
 * request for two projectors that Technical Support reserved for exactly that time. */
async function eventWithApprovedEquipment(request: APIRequestContext, prefix: string, day: string) {
  const eventName = uniqueName(prefix)
  const eventId = await planningEventByApi(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, await apiToken(request, 'ec1'), '/api/equipment/catalogue')
  const projector = catalogue.find((e) => e.equipmentName === 'Projector')!
  const created = await request.post(`/api/events/${eventId}/equipment-request`, { headers: await headers(request, 'ec1'),
    data: { technicalRequirement: 'Two projectors for the main stage.', items: [{ equipmentId: projector.equipmentId, quantity: 2 }] } })
  expect(created.ok(), await created.text()).toBeTruthy()
  const ts = await headers(request, 'ts1')
  const reserved = await request.post('/api/equipment/reservations', { headers: ts, data: { eventId, equipmentId: projector.equipmentId,
    quantity: 2, start: `${day}T01:00:00Z`, end: `${day}T04:00:00Z` } })
  expect(reserved.ok(), await reserved.text()).toBeTruthy()
  const approved = await request.patch(`/api/equipment-requests/${(await created.json()).requestId}/status`, { headers: ts, data: { status: 'approved' } })
  expect(approved.ok(), await approved.text()).toBeTruthy()
  return { eventId, eventName, requestId: await requestIdOf(request, eventName) }
}

async function equipmentRequestOf(request: APIRequestContext, eventId: string) {
  const all = await apiGet<{ requestId: string; status: string; attentionReason: string | null; lines: unknown[] }[]>(
    request, await apiToken(request, 'ec1'), `/api/events/${eventId}/equipment-requests`)
  return all.find((r) => r.status !== 'rejected' && r.status !== 'superseded')!
}

async function reservationWindows(request: APIRequestContext, eventId: string) {
  return (await apiGet<{ quantity: number; loanedFrom: string; loanedUntil: string }[]>(
    request, await apiToken(request, 'ts1'), `/api/equipment/events/${eventId}/reservations`))
    .map((r) => `${r.quantity} ${new Date(r.loanedFrom).toISOString()}–${new Date(r.loanedUntil).toISOString()}`)
}

test('EC-NEW3 AC24, AC25, AC36, AC39 (negative): the coordinator rejects a change with a reason; the event keeps its details and the organiser is told why', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const day = uniqueDay()
  const eventName = uniqueName('E2E Rejected Change')
  const eventId = await planningEventByApi(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const requestId = await requestIdOf(request, eventName)
  const changeId = await askForChange(request, requestId, { expectedAttendance: 220 }, 'Two more departments want to come.')
  const reason = `The hall cannot seat 220 people for ${eventName}.`

  await test.step('EC-NEW3 AC25: a whitespace-only reason is refused even by a direct API call, and the request stays pending', async () => {
    const refused = await request.post(`/api/change-requests/${changeId}/reject`, { headers: await headers(request, 'ec1'), data: { reason: '   ' } })
    expect(refused.ok(), `blank reason: ${refused.status()}`).toBeFalsy()
    expect((await apiGet<{ status: string }>(request, await apiToken(request, 'ec1'), `/api/change-requests/${changeId}`)).status).toBe('pending')
  })

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC-NEW3 AC24, AC25: the coordinator cannot reject without a reason, then rejects with one', async () => {
    await coordinator.goto(`/coordinator/change-requests/${changeId}`)
    await coordinator.getByRole('button', { name: 'Reject…' }).click()
    const reject = coordinator.getByRole('button', { name: 'Reject the change' })
    const why = coordinator.getByLabel("Why can't this change be made?")
    await expect(reject).toBeDisabled()
    await why.fill('   ')
    await expect(reject).toBeDisabled()
    await why.fill(reason)
    await reject.click()
    await expect(coordinator.getByText('Change rejected. The organiser has been told why.')).toBeVisible()
  })

  await test.step('EC-NEW3 AC36: the event keeps its details and status', async () => {
    const event = await apiGet<{ expectedAttendance: number; status: string }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)
    expect(event.expectedAttendance).toBe(150)
    expect(event.status).toBe('pending')
  })

  await test.step('EC-NEW3 AC39: the organiser is told of the rejection and its reason', async () => {
    const organiser = await signedIn(browser, 'eo1')
    await expect((await openBell(organiser)).getByRole('listitem').filter({ hasText: `Your change request was rejected: ${reason}` })).toHaveCount(1)
    await organiser.goto(`/organiser/requests/${requestId}/changes`)
    await expect(organiser.getByText('Rejected by ec1')).toBeVisible()
    await expect(organiser.getByText(reason)).toBeVisible()
  })
})

test('EC-NEW3 AC32, AC33 (happy): a change to the equipment requirements flags the approved equipment request; the coordinator marks it still suitable', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const day = uniqueDay()
  const { eventId, requestId } = await eventWithApprovedEquipment(request, 'E2E Wording Change', day)
  const before = await reservationWindows(request, eventId)
  await approveChange(request, await askForChange(request, requestId,
    { equipmentRequirements: 'Two projectors and a lectern microphone.' }, 'The speaker needs a microphone.'))

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC-NEW3 AC32, AC33: approving moves no reservation, and the event page flags the equipment request', async () => {
    expect(await reservationWindows(request, eventId)).toEqual(before)
    expect((await equipmentRequestOf(request, eventId)).status).toBe('attention')
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByRole('paragraph').filter({ hasText: "The equipment request needs attention: The event's equipment requirements text changed." })).toBeVisible()
  })

  await test.step('EC-NEW3 (D37): the coordinator explains why it still suits; it is Approved again with its items and reservations', async () => {
    await coordinator.getByRole('link', { name: 'Resolve equipment request' }).click()
    await expect(coordinator.getByText('Needs attention', { exact: true })).toBeVisible()
    await coordinator.getByRole('button', { name: 'Mark still suitable' }).click()
    const confirm = coordinator.getByRole('button', { name: 'Confirm still suitable' })
    await expect(confirm).toBeDisabled()
    await coordinator.getByLabel('Why is it still suitable?').fill('The venue provides its own lectern microphone.')
    await confirm.click()
    await expect(coordinator.getByText('Approved', { exact: true })).toBeVisible()
    const after = await equipmentRequestOf(request, eventId)
    expect(after.status).toBe('approved')
    expect(after.lines).toHaveLength(1)
    expect(await reservationWindows(request, eventId)).toEqual(before)
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByRole('heading', { name: 'Equipment' })).toBeVisible()
    await expect(coordinator.getByRole('link', { name: 'Resolve equipment request' })).toHaveCount(0)
    await expect(coordinator.getByText(/The equipment request needs attention/)).toHaveCount(0)
  })
})

test('EC-NEW3 AC32, AC33 (conflict): a time change the reservations no longer cover flags the equipment request; it cannot be marked still suitable', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const day = uniqueDay()
  const { eventId, requestId } = await eventWithApprovedEquipment(request, 'E2E Moved Equipment', day)
  const before = await reservationWindows(request, eventId)
  await approveChange(request, await askForChange(request, requestId,
    { startDatetime: `${day}T14:00:00+08:00`, endDatetime: `${day}T17:00:00+08:00` }, 'The keynote moved to the afternoon.'))
  const reason = "The event's new time no longer matches an existing equipment reservation."

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC-NEW3 AC32, AC33: the reservations stay at the old time, and the event page flags the equipment request', async () => {
    expect(await reservationWindows(request, eventId)).toEqual(before)
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByRole('paragraph').filter({ hasText: `The equipment request needs attention: ${reason}` })).toBeVisible()
  })

  await test.step('EC-NEW3 (D37): marking it still suitable is refused, and nothing changes', async () => {
    await coordinator.getByRole('link', { name: 'Resolve equipment request' }).click()
    await coordinator.getByRole('button', { name: 'Mark still suitable' }).click()
    await coordinator.getByLabel('Why is it still suitable?').fill('The projectors are free all day.')
    await coordinator.getByRole('button', { name: 'Confirm still suitable' }).click()
    await expect(coordinator.getByText("A reservation no longer covers the event's time. Revise the request instead.")).toBeVisible()
    const after = await equipmentRequestOf(request, eventId)
    expect(after.status).toBe('attention')
    expect(after.attentionReason).toBe(reason)
    expect(await reservationWindows(request, eventId)).toEqual(before)
  })
})
