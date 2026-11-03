// @stories EO03 EC-NEW3 EC07 EC10 TS01 TS02 TS06 VS03 VS04 EC08
import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, planningEventByApi, requestIdOf, signedIn, uniqueDay,
  uniqueName } from './support.ts'

// The change-request journeys the user specified (3 November 2026), end to end
// across EO, EC, VS and TS. The rule under test (D40): an approved change never
// drops an existing booking or reservation. The coordinator asks for a
// replacement or a revision, the old arrangement is kept until Venue Staff or
// Technical Support accept the new one, and only then is it superseded; if they
// reject it, the old arrangement stays and the event stays in Planning.
// Fixtures come through the API; every decision is made in the browser.

type Booking = { bookingId: string; venueId: string; status: string; replacesBookingId?: string | null }
type EquipmentRequest = { requestId: string; status: string; revision?: boolean; lines: { quantity: number }[] }
type Reservation = { equipmentName: string; quantity: number; loanedFrom: string; loanedUntil: string }

async function auth(request: APIRequestContext, username: string) {
  return { Authorization: `Bearer ${await apiToken(request, username)}` }
}

async function screenId(request: APIRequestContext) {
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request,
    await apiToken(request, 'ec1'), '/api/equipment/catalogue')
  return catalogue.find((item) => item.equipmentName === 'Projection Screen')!.equipmentId
}

/** Test data: a Confirmed event of eo1's on `day`, 09:00-12:00 Singapore time, with its venue approved and an
 * approved equipment request for `screens` Projection Screens that Technical Support reserved for that time. */
async function confirmedWithScreens(request: APIRequestContext, prefix: string, day: string, screens: number) {
  const eventName = uniqueName(prefix)
  const venueName = uniqueName(`${prefix} Hall`)
  const venueId = await createVenue(request, venueName)
  const equipmentId = await screenId(request)
  const eventId = await confirmedEventByApi(request, eventName, venueId, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`,
    undefined, async (id) => {
      const asked = await request.post(`/api/events/${id}/equipment-request`, { headers: await auth(request, 'ec1'),
        data: { technicalRequirement: 'Screens for the stage.', items: [{ equipmentId, quantity: screens }] } })
      expect(asked.ok(), await asked.text()).toBeTruthy()
      const ts = await auth(request, 'ts1')
      const reserved = await request.post('/api/equipment/reservations', { headers: ts, data: { eventId: id, equipmentId,
        quantity: screens, start: `${day}T01:00:00Z`, end: `${day}T04:00:00Z` } })
      expect(reserved.ok(), await reserved.text()).toBeTruthy()
      const approved = await request.patch(`/api/equipment-requests/${(await asked.json()).requestId}/status`,
        { headers: ts, data: { status: 'approved' } })
      expect(approved.ok(), await approved.text()).toBeTruthy()
    })
  return { eventId, eventName, venueId, venueName, requestId: await requestIdOf(request, eventName) }
}

const bookingsOf = async (request: APIRequestContext, eventId: string) =>
  apiGet<Booking[]>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}/venue-bookings`)
const equipmentRequestsOf = async (request: APIRequestContext, eventId: string) =>
  apiGet<EquipmentRequest[]>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}/equipment-requests`)
const eventOf = async (request: APIRequestContext, eventId: string) =>
  apiGet<{ status: string }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)
/** The event's reservations as "<quantity> <from>–<until>" in UTC, so windows compare exactly. */
const reservationsOf = async (request: APIRequestContext, eventId: string) =>
  (await apiGet<Reservation[]>(request, await apiToken(request, 'ts1'), `/api/equipment/events/${eventId}/reservations`))
    .map((r) => `${r.quantity} ${new Date(r.loanedFrom).toISOString()}–${new Date(r.loanedUntil).toISOString()}`).sort()

/** EO03: the organiser asks, in the browser, for the event to move to 14:00-17:00 the same day. */
async function organiserMovesTheEvent(organiser: Page, eventId: string, day: string) {
  await organiser.goto(`/organiser/events/${eventId}/change`)
  await organiser.getByLabel('Start date & time').fill(`${day}T14:00`)
  await organiser.getByLabel('End date & time').fill(`${day}T17:00`)
  await organiser.getByRole('button', { name: 'Review the change' }).click()
  await organiser.getByLabel('Why is this change needed?').fill('The keynote moved to the afternoon.')
  await organiser.getByRole('button', { name: 'Send change request' }).click()
  await expect(organiser.getByText(/Change request sent/)).toBeVisible()
}

/** EC-NEW3: the coordinator opens the pending change from the event page. */
async function openPendingChange(coordinator: Page, eventId: string) {
  await coordinator.goto(`/coordinator/events/${eventId}`)
  const pending = coordinator.getByRole('region', { name: 'Change requested' })
  await expect(pending).toBeVisible({ timeout: 15_000 })
  await pending.getByRole('link', { name: 'Review and decide' }).click()
}

async function acknowledgeAndApprove(coordinator: Page) {
  // The checkboxes render with the button once the impact loads; .all() does not wait.
  await expect(coordinator.getByRole('button', { name: 'Approve and apply the change' })).toBeVisible()
  for (const box of await coordinator.getByRole('checkbox', { name: 'I have considered this' }).all()) await box.check()
  await coordinator.getByRole('button', { name: 'Approve and apply the change' }).click()
  await expect(coordinator.getByText(/Change approved and applied/)).toBeVisible()
}

/** EC10: the coordinator asks to reschedule the booking at the same venue, for the event's new time. */
async function coordinatorReschedulesTheVenue(coordinator: Page, eventId: string, venueName: string) {
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await coordinator.getByRole('link', { name: 'Replace or reschedule this booking' }).click()
  await coordinator.getByPlaceholder('Search by address or details').fill(venueName)
  await coordinator.getByRole('button', { name: new RegExp(venueName) }).first().click()
  await coordinator.getByRole('dialog').getByRole('button', { name: /^(Add to request|Remove from request)$/ }).click()
  await expect(coordinator.getByText('The original booking stays unchanged until Venue Staff approves a replacement.')).toBeVisible()
  await coordinator.getByRole('button', { name: 'Request replacement' }).click()
}

/** EC-NEW3 (D36): the coordinator revises the equipment request, pre-filled with the event's approved items. */
async function coordinatorRevisesEquipment(coordinator: Page, eventId: string, screens: number) {
  await coordinator.goto(`/coordinator/events/${eventId}/equipment-request`)
  await coordinator.getByRole('button', { name: 'Revise request' }).click()
  await expect(coordinator.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true })).toHaveValue(String(screens))
  await coordinator.getByRole('button', { name: 'Submit revision' }).click()
  await expect(coordinator.getByText(/the current request and its reservations stay until they approve it/)).toBeVisible()
}

async function newest<T>(read: () => Promise<T[]>, match: (item: T) => boolean): Promise<T> {
  let found: T | undefined
  await expect.poll(async () => { found = (await read()).find(match); return found !== undefined }).toBe(true)
  return found!
}

async function closeAll(...pages: Page[]) {
  for (const page of pages) await page.context().close()
}

async function signedInAll(browser: Browser, ...usernames: string[]) {
  return Promise.all(usernames.map((username) => signedIn(browser, username)))
}

test('EO03 EC-NEW3 EC10 VS03 TS02 TS06 (happy): a time change keeps the old booking and reservations until Venue Staff and Technical Support accept the new ones, which then supersede them', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const day = uniqueDay()
  const { eventId, venueName } = await confirmedWithScreens(request, 'E2E Moved Gala', day, 2)
  const [original] = await bookingsOf(request, eventId)
  const oldWindow = [`2 ${day}T01:00:00.000Z–${day}T04:00:00.000Z`]
  const newWindow = [`2 ${day}T06:00:00.000Z–${day}T09:00:00.000Z`]
  const [organiser, coordinator, staff, technician] = await signedInAll(browser, 'eo1', 'ec1', 'vs1', 'ts1')

  await test.step('EO03: the organiser asks to move the confirmed event to the afternoon', async () => {
    await organiserMovesTheEvent(organiser, eventId, day)
  })

  await test.step('EC-NEW3: the coordinator sees the venue and the screens can be had at the new time, and approves; the event returns to Planning', async () => {
    await openPendingChange(coordinator, eventId)
    await expect(coordinator.getByText(`${venueName} is booked for the old time`)).toBeVisible()
    await expect(coordinator.getByText('Projection Screen is free at the new time')).toBeVisible()
    await acknowledgeAndApprove(coordinator)
    expect((await eventOf(request, eventId)).status).toBe('pending')
  })

  await test.step('EC10: the coordinator asks to reschedule the venue; the original booking is kept meanwhile, flagged for the new time', async () => {
    await coordinatorReschedulesTheVenue(coordinator, eventId, venueName)
    await newest(() => bookingsOf(request, eventId), (b) => b.replacesBookingId === original.bookingId && b.status === 'pending')
    expect((await bookingsOf(request, eventId)).find((b) => b.bookingId === original.bookingId)!.status).toBe('attention')
  })

  await test.step('EC-NEW3 (D40): the coordinator revises the equipment; the old request and its reservation at the old time are kept', async () => {
    await coordinatorRevisesEquipment(coordinator, eventId, 2)
    const requests = await equipmentRequestsOf(request, eventId)
    expect(requests.find((r) => r.status === 'processing')?.revision).toBe(true)
    expect(requests.find((r) => r.status === 'attention')).toBeTruthy()
    expect(await reservationsOf(request, eventId)).toEqual(oldWindow)
  })

  await test.step('VS03: Venue Staff approve the rescheduled booking, which supersedes the original', async () => {
    const replacement = (await bookingsOf(request, eventId)).find((b) => b.replacesBookingId === original.bookingId)!
    await staff.goto(`/venue-staff/bookings/${replacement.bookingId}`)
    await staff.getByRole('button', { name: 'Approve Booking' }).click()
    await staff.getByRole('button', { name: 'Confirm approval' }).click()
    await expect.poll(async () => (await bookingsOf(request, eventId))
      .find((b) => b.bookingId === replacement.bookingId)?.status).toBe('approved')
    expect((await bookingsOf(request, eventId)).find((b) => b.bookingId === original.bookingId)!.status).toBe('changed')
  })

  await test.step('TS02 TS06: Technical Support reserve the screens for the new time and approve; the old request and reservation are superseded', async () => {
    const revision = (await equipmentRequestsOf(request, eventId)).find((r) => r.status === 'processing')!
    await technician.goto(`/technical-support/requests/${revision.requestId}`)
    await technician.getByRole('button', { name: 'Reserve all remaining (2 units)' }).click()
    await expect(technician.getByText('Reserved 2 units for this event.')).toBeVisible()
    await technician.getByRole('button', { name: 'Approve request' }).click()
    await expect.poll(async () => (await equipmentRequestsOf(request, eventId))
      .find((r) => r.requestId === revision.requestId)?.status).toBe('approved')
    const statuses = (await equipmentRequestsOf(request, eventId)).map((r) => r.status).sort()
    expect(statuses).toEqual(['approved', 'superseded'])
    expect(await reservationsOf(request, eventId)).toEqual(newWindow)
  })

  await test.step('EC08: nothing is outstanding, so the coordinator can send the event for its safety check again', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByRole('button', { name: /Send for safety review/i })).toBeEnabled({ timeout: 15_000 })
  })
  await closeAll(organiser, coordinator, staff, technician)
})

test('EO03 EC-NEW3 TS02 TS06 (happy): more screens: the coordinator revises, the two already reserved are kept, and Technical Support reserve only the extra one', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const day = uniqueDay()
  const { eventId } = await confirmedWithScreens(request, 'E2E Bigger Stage', day, 2)
  const window = `${day}T01:00:00.000Z–${day}T04:00:00.000Z`
  const [organiser, coordinator, technician] = await signedInAll(browser, 'eo1', 'ec1', 'ts1')

  await test.step('EO03: the organiser asks for three screens instead of two', async () => {
    await organiser.goto(`/organiser/events/${eventId}/change`)
    await organiser.getByRole('button', { name: 'Add or remove equipment' }).click()
    await organiser.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true }).fill('3')
    await organiser.getByRole('button', { name: 'Review the change' }).click()
    await expect(organiser.getByRole('table')).toContainText('3 × Projection Screen')
    await organiser.getByLabel('Why is this change needed?').fill('A third screen for the back rows.')
    await organiser.getByRole('button', { name: 'Send change request' }).click()
    await expect(organiser.getByText(/Change request sent/)).toBeVisible()
  })

  await test.step('EC-NEW3 (D40): the coordinator sees three screens can be had, and approves', async () => {
    await openPendingChange(coordinator, eventId)
    await expect(coordinator.getByText('3 Projection Screen can be had')).toBeVisible()
    await acknowledgeAndApprove(coordinator)
  })

  await test.step('EC-NEW3 (D40): the revision keeps the two reserved screens and the approved request', async () => {
    await coordinatorRevisesEquipment(coordinator, eventId, 3)
    expect(await reservationsOf(request, eventId)).toEqual([`2 ${window}`])
    expect((await equipmentRequestsOf(request, eventId)).filter((r) => r.status !== 'processing')
      .map((r) => r.status)).toEqual(['attention'])
  })

  await test.step('TS02 TS06: only one more screen is left to reserve; approving keeps all three and supersedes the old request', async () => {
    const revision = (await equipmentRequestsOf(request, eventId)).find((r) => r.status === 'processing')!
    await technician.goto(`/technical-support/requests/${revision.requestId}`)
    await technician.getByRole('button', { name: 'Reserve all remaining (1 unit)' }).click()
    await expect(technician.getByText('Reserved 1 unit for this event.')).toBeVisible()
    await technician.getByRole('button', { name: 'Approve request' }).click()
    await expect.poll(async () => (await equipmentRequestsOf(request, eventId))
      .find((r) => r.requestId === revision.requestId)?.status).toBe('approved')
    expect((await equipmentRequestsOf(request, eventId)).map((r) => r.status).sort()).toEqual(['approved', 'superseded'])
    expect(await reservationsOf(request, eventId)).toEqual([`1 ${window}`, `2 ${window}`])
  })
  await closeAll(organiser, coordinator, technician)
})

test('EO03 EC-NEW3 EC10 VS04 TS06 (negative): Venue Staff and Technical Support reject the new arrangements; the old booking and reservations are kept and the event stays in Planning', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const day = uniqueDay()
  const { eventId, venueName } = await confirmedWithScreens(request, 'E2E Refused Move', day, 2)
  const [original] = await bookingsOf(request, eventId)
  const oldWindow = [`2 ${day}T01:00:00.000Z–${day}T04:00:00.000Z`]
  const [organiser, coordinator, staff, technician] = await signedInAll(browser, 'eo1', 'ec1', 'vs1', 'ts1')

  await test.step('EO03 EC-NEW3: the organiser moves the event; the coordinator approves, asks to reschedule the venue and revises the equipment', async () => {
    await organiserMovesTheEvent(organiser, eventId, day)
    await openPendingChange(coordinator, eventId)
    await acknowledgeAndApprove(coordinator)
    await coordinatorReschedulesTheVenue(coordinator, eventId, venueName)
    await newest(() => bookingsOf(request, eventId), (b) => b.replacesBookingId === original.bookingId)
    await coordinatorRevisesEquipment(coordinator, eventId, 2)
  })

  await test.step('VS04: Venue Staff reject the rescheduled booking with a reason; the original booking is still kept', async () => {
    const replacement = (await bookingsOf(request, eventId)).find((b) => b.replacesBookingId === original.bookingId)!
    await staff.goto(`/venue-staff/bookings/${replacement.bookingId}`)
    await staff.getByRole('button', { name: 'Reject Booking' }).click()
    await staff.getByLabel('Rejection reason').fill('The hall is taken for a wedding that afternoon.')
    await staff.getByRole('button', { name: 'Confirm rejection' }).click()
    await expect.poll(async () => (await bookingsOf(request, eventId))
      .find((b) => b.bookingId === replacement.bookingId)?.status).toBe('rejected')
    expect((await bookingsOf(request, eventId)).find((b) => b.bookingId === original.bookingId)!.status).toBe('attention')
  })

  await test.step('TS02 TS06: Technical Support reserve for the revision, then reject it with a reason; only its own reservation goes back', async () => {
    const revision = (await equipmentRequestsOf(request, eventId)).find((r) => r.status === 'processing')!
    await technician.goto(`/technical-support/requests/${revision.requestId}`)
    await technician.getByRole('button', { name: 'Reserve all remaining (2 units)' }).click()
    await expect(technician.getByText('Reserved 2 units for this event.')).toBeVisible()
    expect(await reservationsOf(request, eventId)).toHaveLength(2)
    await technician.getByRole('button', { name: 'Reject…' }).click()
    await technician.getByLabel('Reason for rejection').fill('No screens are free that afternoon.')
    await technician.getByRole('button', { name: 'Reject request' }).click()
    await expect(technician.getByText('This request was rejected.')).toBeVisible()
    await expect.poll(async () => (await equipmentRequestsOf(request, eventId))
      .find((r) => r.requestId === revision.requestId)?.status).toBe('rejected')
    expect((await equipmentRequestsOf(request, eventId)).find((r) => r.requestId !== revision.requestId)!.status).toBe('attention')
    expect(await reservationsOf(request, eventId)).toEqual(oldWindow)
  })

  await test.step('EC08: the event stays in Planning with the equipment outstanding, so it cannot go for its safety check', async () => {
    expect((await eventOf(request, eventId)).status).toBe('pending')
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByText(/The equipment request needs attention/).first()).toBeVisible()
    // Not ready: the readiness panel lists blockers and offers no Send button (as golden-path checks).
    await expect(coordinator.getByText('Not ready yet')).toBeVisible()
    await expect(coordinator.getByRole('button', { name: /Send for safety review/i })).toHaveCount(0)
  })
  await closeAll(organiser, coordinator, staff, technician)
})

test('EO03 EC-NEW3 (negative): the organiser asks for more screens than are free at the event\'s time; the coordinator sees it and rejects the change with a reason', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const day = uniqueDay()
  const eventName = uniqueName('E2E Too Many Screens Change')
  const eventId = await planningEventByApi(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const requestId = await requestIdOf(request, eventName)
  const reason = 'Only 6 projection screens exist, so 7 cannot be provided.'
  const [organiser, coordinator] = await signedInAll(browser, 'eo1', 'ec1')

  await test.step('EO03: the organiser asks for 7 screens; 6 are in stock', async () => {
    await organiser.goto(`/organiser/events/${eventId}/change`)
    await organiser.getByRole('button', { name: 'Add or remove equipment' }).click()
    await organiser.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true }).fill('7')
    await organiser.getByRole('button', { name: 'Review the change' }).click()
    await organiser.getByLabel('Why is this change needed?').fill('Every breakout room needs a screen.')
    await organiser.getByRole('button', { name: 'Send change request' }).click()
    await expect(organiser.getByText(/Change request sent/)).toBeVisible()
  })

  await test.step('EC-NEW3 (D40): the impact says there are not enough screens at that time', async () => {
    await openPendingChange(coordinator, eventId)
    await expect(coordinator.getByText('Not enough Projection Screen for the requested quantity')).toBeVisible()
    await expect(coordinator.getByText(/Only 6 of the 7 asked for is free/)).toBeVisible()
  })

  await test.step('EC-NEW3 AC24, AC25: the coordinator rejects with a reason; the event keeps its equipment and the organiser sees why', async () => {
    await coordinator.getByRole('button', { name: 'Reject…' }).click()
    await coordinator.getByLabel("Why can't this change be made?").fill(reason)
    await coordinator.getByRole('button', { name: 'Reject the change' }).click()
    await expect(coordinator.getByText('Change rejected. The organiser has been told why.')).toBeVisible()
    const event = await apiGet<{ equipmentItems: unknown[]; status: string }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)
    expect(event.equipmentItems).toEqual([])
    expect(event.status).toBe('pending')
    await organiser.goto(`/organiser/requests/${requestId}/changes`)
    await expect(organiser.getByText(reason)).toBeVisible()
  })
  await closeAll(organiser, coordinator)
})

test('EC07 (boundary): the coordinator asks for more screens than are free at the event\'s time, lowers it to what is free, and sends it', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const day = uniqueDay()
  const eventId = await planningEventByApi(request, uniqueName('E2E Adjusted Screens'), `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  // Another event already holds 2 of the 6 screens at the same time, so 4 are free.
  const other = await planningEventByApi(request, uniqueName('E2E Screen Holder'), `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const equipmentId = await screenId(request)
  const held = await request.post(`/api/events/${other}/equipment-request`, { headers: await auth(request, 'ec1'),
    data: { technicalRequirement: 'Two screens.', items: [{ equipmentId, quantity: 2 }] } })
  expect(held.ok(), await held.text()).toBeTruthy()
  const reserved = await request.post('/api/equipment/reservations', { headers: await auth(request, 'ts1'),
    data: { eventId: other, equipmentId, quantity: 2, start: `${day}T01:00:00Z`, end: `${day}T04:00:00Z` } })
  expect(reserved.ok(), await reserved.text()).toBeTruthy()
  const coordinator = await signedIn(browser, 'ec1')

  await test.step('EC07 (negative): five screens are flagged as more than are free and cannot be sent', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/equipment-request`)
    await coordinator.getByRole('button', { name: 'Edit equipment' }).click()
    await coordinator.getByLabel('Projection Screen', { exact: true }).check()
    await coordinator.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true }).fill('5')
    await expect(coordinator.getByRole('alert')).toContainText('Projection Screen: 5 selected, only 4 free')
    await expect(coordinator.getByRole('button', { name: 'Submit equipment request' })).toBeDisabled()
  })

  await test.step('EC07 (boundary): lowered to exactly the four free, it is sent to Technical Support', async () => {
    await coordinator.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true }).fill('4')
    await expect(coordinator.getByRole('alert')).toHaveCount(0)
    await coordinator.getByRole('button', { name: 'Submit equipment request' }).click()
    await expect(coordinator.getByText('Equipment request submitted to Technical Support for review.')).toBeVisible()
    const [sent] = await equipmentRequestsOf(request, eventId)
    expect(sent.status).toBe('processing')
    expect(sent.lines.map((l) => l.quantity)).toEqual([4])
  })
  await coordinator.context().close()
})
