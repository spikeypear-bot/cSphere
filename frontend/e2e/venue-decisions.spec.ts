// @stories EC04 EC05 VS04 VS09 EC10 VS25 EC-C4 EC-C3 VS-C4 EC-NEW4
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, createVenue, openBell, planningEventByApi, signedIn, uniqueName, addVenueToRequest } from './support.ts'

// Finding, comparing, booking and losing venues, through the coordinator's and
// Venue Staff's screens. Expected wording comes from the EC04, EC05, VS04,
// VS09, EC10, VS25, EC-C4 and EC-C3 ACs (Sprint Backlogs 2–4).

async function auth(request: APIRequestContext, username: string) {
  return { Authorization: `Bearer ${await apiToken(request, username)}` }
}

async function bookingsOf(request: APIRequestContext, eventId: string) {
  return apiGet<{ bookingId: string; venueAddress?: string; venueId: string; status: string }[]>(
    request, await apiToken(request, 'ec1'), `/api/events/${eventId}/venue-bookings`)
}

async function markUnavailable(request: APIRequestContext, venueId: string, startDateTime: string, endDateTime: string, reason: string) {
  const headers = await auth(request, 'vs1')
  const period = { startDateTime, endDateTime, reason }
  const preview = await request.post(`/api/venues/${venueId}/unavailability/preview`, { headers, data: period })
  expect(preview.ok(), await preview.text()).toBeTruthy()
  const { affectedBookings, previewToken } = await preview.json()
  const saved = await request.post(`/api/venues/${venueId}/unavailability`, { headers, data: { ...period, previewToken,
    acknowledgedBookingIds: affectedBookings.map((b: { bookingId: string }) => b.bookingId) } })
  expect(saved.ok(), await saved.text()).toBeTruthy()
}

test('EC04 (happy): venue search keeps only venues that are free, big enough and have every required facility', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const exact = uniqueName('E2E Exact 777')
  const stage = uniqueName('E2E Stage 800')
  const noStage = uniqueName('E2E Projector 800')
  await createVenue(request, exact, 777)
  const stageId = await createVenue(request, stage, 800, ['stage'])
  await createVenue(request, noStage, 800, ['projection'])

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/venue-search')
  const results = coordinator.locator('section, div').filter({ has: coordinator.getByRole('heading', { name: 'Search results' }) }).last()
  async function search(capacity: string) {
    await coordinator.getByLabel('Event starts').fill('2027-11-02T09:00')
    await coordinator.getByLabel('Event ends').fill('2027-11-02T12:00')
    await coordinator.getByLabel('Required capacity').fill(capacity)
    await coordinator.getByRole('button', { name: 'Search venues' }).click()
  }

  await test.step('EC04 AC3, AC4, AC5 (boundary): a venue whose capacity equals the requirement is included; one more and it is not', async () => {
    await search('777')
    await expect(results.getByRole('button', { name: exact })).toBeVisible()
    await search('778')
    await expect(results.getByRole('button', { name: noStage })).toBeVisible()
    await expect(results.getByRole('button', { name: exact })).toHaveCount(0)
  })

  await test.step('EC04 AC6, AC7, AC8: with Stage required as well, only venues with a stage remain', async () => {
    await coordinator.getByRole('checkbox', { name: 'Stage' }).check()
    await search('800')
    await expect(results.getByRole('button', { name: stage })).toBeVisible()
    await expect(results.getByRole('button', { name: noStage })).toHaveCount(0)
  })

  await test.step('EC04 AC1, AC2: a venue unavailable at that time drops out of the same search', async () => {
    await markUnavailable(request, stageId, '2027-11-02T08:00:00+08:00', '2027-11-02T13:00:00+08:00', 'Repainting')
    await search('800')
    await expect(results.getByRole('button', { name: stage })).toHaveCount(0)
  })

  await test.step('EC04 AC10, AC11 (negative): an end before the start, and a capacity of 0, are refused with a reason', async () => {
    await coordinator.getByLabel('Event starts').fill('2027-11-02T12:00')
    await coordinator.getByLabel('Event ends').fill('2027-11-02T09:00')
    await coordinator.getByLabel('Required capacity').fill('100')
    await coordinator.getByRole('button', { name: 'Search venues' }).click()
    await expect(coordinator.getByRole('alert')).toHaveText('End date and time must be after the start date and time.')
    await coordinator.getByLabel('Event ends').fill('2027-11-02T15:00')
    await coordinator.getByLabel('Required capacity').fill('0')
    await coordinator.getByRole('button', { name: 'Search venues' }).click()
    await expect(coordinator.getByRole('alert')).toHaveText('Required capacity must be a whole number greater than 0.')
  })
})

test('EC05 VS04 VS09 EC10 VS25 (happy): compare, book, be rejected with an alternative, and rebook; the released venue is free again', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('E2E Rebook Gala')
  const small = uniqueName('E2E Small Room')
  const hall = uniqueName('E2E Harbour Hall')
  const marina = uniqueName('E2E Marina Hall')
  await createVenue(request, small, 100)
  const hallId = await createVenue(request, hall, 300)
  const marinaId = await createVenue(request, marina, 300)
  const eventId = await planningEventByApi(request, eventName, '2027-11-09T09:00:00+08:00', '2027-11-09T12:00:00+08:00')

  const coordinator = await signedIn(browser, 'ec1')
  const compare = coordinator.locator('.venue-request__compare')
  await test.step('EC05 AC1, AC2, AC5: a venue too small for 150 people is shown as such', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await coordinator.getByPlaceholder('Search by address or details').fill(small)
    await expect(coordinator.getByText('No venues match. Try showing unavailable venues to see why.')).toBeVisible()
    await coordinator.getByRole('checkbox', { name: 'Show unavailable venues' }).check()
    await addVenueToRequest(coordinator, new RegExp(small))
    await expect(compare.getByRole('row', { name: /Capacity/ })).toContainText('150 people')
    await expect(compare.getByRole('row', { name: /Capacity/ })).toContainText('100 ✗')
    await expect(coordinator.getByText('Capacity 100 is below the expected attendance of 150.').first()).toBeVisible()
    await expect(coordinator.getByText(`${small} cannot be booked yet`)).toBeVisible()
    await expect(coordinator.getByRole('button', { name: `Request ${small}` })).toBeDisabled()
  })

  await test.step('EC05 AC3, AC4, AC7: a suitable, free venue shows every requirement met, and can be requested', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await coordinator.getByPlaceholder('Search by address or details').fill(hall)
    await addVenueToRequest(coordinator, new RegExp(hall))
    await expect(compare.getByRole('row', { name: /Capacity/ })).toContainText('300 ✓')
    await expect(compare.getByRole('row', { name: /Availability/ })).toContainText('Available ✓')
    await expect(compare.getByRole('row', { name: /Required facilities/ })).toContainText('All provided ✓')
    await coordinator.getByRole('button', { name: `Request ${hall}` }).click()
    await expect(coordinator.getByText(`Booking request for ${hall} sent to Venue Staff`)).toBeVisible()
  })

  const bookingId = (await bookingsOf(request, eventId)).find((b) => b.venueId === hallId)!.bookingId
  const staff = await signedIn(browser, 'vs1')
  await test.step('VS04 AC3, AC4 (negative): Venue Staff cannot confirm a rejection without a reason', async () => {
    await staff.goto(`/venue-staff/bookings/${bookingId}`)
    await staff.getByRole('button', { name: 'Reject Booking' }).click()
    await staff.getByRole('button', { name: 'Confirm rejection' }).click()
    await expect(staff.getByLabel('Rejection reason')).toBeVisible()
    expect((await bookingsOf(request, eventId)).find((b) => b.bookingId === bookingId)!.status).toBe('pending')
  })

  await test.step('VS04 AC5, AC6, AC10 / VS09 AC3, AC4, AC5: rejected with a reason, an alternative venue and an arrangement', async () => {
    await staff.getByLabel('Rejection reason').fill('The hall is closed for floor repairs.')
    await staff.getByLabel('Alternative venue (optional)').selectOption({ label: marina })
    await staff.getByLabel('Alternative arrangement (optional)').fill('Marina Hall has the same layout.')
    await staff.getByRole('button', { name: 'Confirm rejection' }).click()
    await expect(staff.getByLabel('Rejection reason').or(staff.getByText('The hall is closed for floor repairs.')).first()).toBeVisible()
    await expect(staff.getByText('The hall is closed for floor repairs.').first()).toBeVisible()
  })

  await test.step('VS09 AC6: the suggestion does not book the alternative by itself', async () => {
    const bookings = await bookingsOf(request, eventId)
    expect(bookings.find((b) => b.bookingId === bookingId)!.status).toBe('rejected')
    expect(bookings.some((b) => b.venueId === marinaId)).toBeFalsy()
  })

  await test.step('VS04 AC7 / VS09 AC8 / EC-NEW4 AC9: the coordinator is told, and sees the reason and the suggestion', async () => {
    await coordinator.reload()
    const notice = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: eventName })
      .filter({ hasText: 'was rejected' })
    await expect(notice).toContainText('The hall is closed for floor repairs.')
    await expect(notice).toContainText(marina)
    await coordinator.keyboard.press('Escape')
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await expect(coordinator.getByText('The hall is closed for floor repairs.').first()).toBeVisible()
    await expect(coordinator.getByText('Marina Hall has the same layout.').first()).toBeVisible()
  })

  await test.step('VS25 AC1, AC5: the rejected booking no longer blocks the hall; another event can book it for the same time', async () => {
    const other = await planningEventByApi(request, uniqueName('E2E Same Slot'), '2027-11-09T09:00:00+08:00', '2027-11-09T12:00:00+08:00')
    const booked = await request.post(`/api/events/${other}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId: hallId } })
    expect(booked.ok(), await booked.text()).toBeTruthy()
    const approved = await request.patch(`/api/venue-bookings/${(await booked.json()).bookingId}/approve`, { headers: await auth(request, 'vs1') })
    expect(approved.ok(), `the released hall can be approved for another event: ${await approved.text()}`).toBeTruthy()
  })

  await test.step('EC10 AC2, AC3, AC5, AC9: the coordinator requests the suggested venue as a replacement; the original stays rejected until it is approved', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await coordinator.getByRole('link', { name: 'Replace or reschedule this booking' }).click()
    await coordinator.getByPlaceholder('Search by address or details').fill(marina)
    await addVenueToRequest(coordinator, new RegExp(marina))
    await expect(coordinator.getByRole('heading', { name: 'Review replacement venue' })).toBeVisible()
    await expect(coordinator.getByText('The original booking stays unchanged until Venue Staff approves a replacement.')).toBeVisible()
    await coordinator.getByRole('button', { name: 'Request replacement' }).click()
    await expect.poll(async () => (await bookingsOf(request, eventId)).find((b) => b.venueId === marinaId)?.status).toBe('pending')
    expect((await bookingsOf(request, eventId)).find((b) => b.bookingId === bookingId)!.status).toBe('rejected')
  })

  await test.step('EC10 AC1 (negative): another coordinator cannot rebook it', async () => {
    const refused = await request.post(`/api/events/${eventId}/venue-bookings`, {
      headers: await auth(request, 'ec2'), data: { venueId: marinaId, replacesBookingId: bookingId } })
    expect(refused.ok()).toBeFalsy()
  })
})

test('EC-C4 (happy): when Venue Staff mark a booked venue unavailable, the coordinator is told which booking, why, and that nothing was cancelled', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Flooded Gala')
  const venueName = uniqueName('E2E Riverside Hall')
  const venueId = await createVenue(request, venueName)
  const eventId = await planningEventByApi(request, eventName, '2027-11-16T09:00:00+08:00', '2027-11-16T12:00:00+08:00')
  const booked = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId } })
  expect(booked.ok(), await booked.text()).toBeTruthy()

  await markUnavailable(request, venueId, '2027-11-16T08:00:00+08:00', '2027-11-16T13:00:00+08:00', 'Flooding in the basement')

  const coordinator = await signedIn(browser, 'ec1')
  const notice = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: `A venue booking for "${eventName}" is unavailable` })
  await test.step('EC-C4 AC1, AC2, AC7: a pending booking is enough; the headline names the event', async () => {
    await expect(notice).toHaveCount(1)
  })
  await test.step('EC-C4 AC3, AC4, AC5, AC6: it names the venue and time window in Singapore time, the reason, and that nothing was cancelled', async () => {
    await expect(notice).toContainText(venueName)
    await expect(notice).toContainText('Flooding in the basement')
    await expect(notice).toContainText(/16 Nov 2027|2027-11-16/)
    await expect(notice).toContainText(/alternative venue arrangements/i)
    await expect(notice).toContainText(/not (been )?cancelled/i)
  })
  await test.step('EC-C4 AC8: it opens the event\'s venue screen to make a replacement', async () => {
    await notice.getByRole('button').click()
    await expect(coordinator).toHaveURL(new RegExp(`/coordinator/events/${eventId}/venue-booking$`))
  })
  await test.step('EC-C4 AC9: another coordinator is not told', async () => {
    const other = await signedIn(browser, 'ec2')
    await expect((await openBell(other)).getByRole('listitem').filter({ hasText: eventName })).toHaveCount(0)
  })
})

test('EC-C3 (happy): a hold entering its last 24 hours reminds the coordinator once, and the booking shows Expiring soon', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Hold Reminder')
  const venueName = uniqueName('E2E Hold Hall')
  const venueId = await createVenue(request, venueName)
  const eventId = await planningEventByApi(request, eventName, '2027-11-23T09:00:00+08:00', '2027-11-23T12:00:00+08:00')
  const booked = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId } })
  expect(booked.ok(), await booked.text()).toBeTruthy()
  const expiresAt = new Date(Date.now() + 6 * 60 * 60 * 1000).toISOString()
  const held = await request.post(`/api/venue-bookings/${(await booked.json()).bookingId}/holds`, {
    headers: await auth(request, 'vs1'), data: { expiresAt } })
  expect(held.ok(), await held.text()).toBeTruthy()

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC-C3 AC1, AC4: a hold created inside the window is reminded at the next check', async () => {
    await expect.poll(async () => {
      const list = await apiGet<{ type: string; eventName: string }[]>(request, await apiToken(request, 'ec1'), '/api/notifications')
      return list.filter((n) => n.type === 'venue_hold_expiring' && n.eventName === eventName).length
    }, { timeout: 20_000 }).toBe(1)
    await coordinator.reload()
    await expect((await openBell(coordinator)).getByText(`Tentative venue hold expiring soon for "${eventName}"`)).toBeVisible()
    await coordinator.keyboard.press('Escape')
  })
  await test.step('EC-C3 AC3: later checks do not remind it again', async () => {
    await coordinator.waitForTimeout(5_000) // two more 2-second checks
    const list = await apiGet<{ type: string; eventName: string }[]>(request, await apiToken(request, 'ec1'), '/api/notifications')
    expect(list.filter((n) => n.type === 'venue_hold_expiring' && n.eventName === eventName)).toHaveLength(1)
  })
  await test.step('EC-C3 AC8: the coordinator\'s booking page shows the hold as Expiring soon', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await expect(coordinator.getByText('Expiring soon').first()).toBeVisible()
  })
})

test('VS-C4 (conflict): a longer setup that would make two bookings overlap is refused, naming the conflicting booking and its occupied period; nothing changes', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const venueName = uniqueName('E2E Conflict Hall')
  const venueId = await createVenue(request, venueName)
  const morning = uniqueName('E2E Morning Talk')
  const afternoon = uniqueName('E2E Afternoon Talk')
  const approvedBooking = async (eventName: string, start: string, end: string) => {
    const eventId = await planningEventByApi(request, eventName, start, end)
    const booked = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId } })
    expect(booked.ok(), await booked.text()).toBeTruthy()
    const bookingId = (await booked.json()).bookingId as string
    const approved = await request.patch(`/api/venue-bookings/${bookingId}/approve`, { headers: await auth(request, 'vs1') })
    expect(approved.ok(), await approved.text()).toBeTruthy()
    return { eventId, bookingId }
  }
  const first = await approvedBooking(morning, '2027-11-30T09:00:00+08:00', '2027-11-30T12:00:00+08:00')
  const second = await approvedBooking(afternoon, '2027-11-30T13:00:00+08:00', '2027-11-30T16:00:00+08:00')

  const staff = await signedIn(browser, 'vs1')
  await test.step('VS-C4 AC3 (boundary): an hour apart, the bookings do not conflict and no conflict panel is shown', async () => {
    await staff.goto(`/venue-staff/catalogue/${venueId}`)
    await expect(staff.getByRole('heading', { name: morning })).toBeVisible()
    await expect(staff.getByRole('region', { name: 'Existing timing conflicts' })).toHaveCount(0)
  })

  await test.step('VS-C4 AC2, AC4, AC6: a 90-minute setup on the afternoon booking is refused, naming the morning booking and its occupied period', async () => {
    const refused = await request.put(`/api/venue-bookings/${second.bookingId}/timing`, { headers: await auth(request, 'vs1'),
      data: { setupDurationMinutes: 90, turnaroundDurationMinutes: 0, timingNote: 'Stage build for the afternoon talk.',
        previousSetupDurationMinutes: 0, previousTurnaroundDurationMinutes: 0 } })
    expect(refused.ok()).toBeFalsy()
    const body = await refused.json()
    expect(body.message).toBe('These timings would introduce or extend a conflict. Reduce the occupied period or resolve the conflicting arrangements first.')
    expect(body.conflicts).toEqual([expect.objectContaining({ bookingId: first.bookingId, eventName: morning,
      effectiveStart: '2027-11-30T01:00:00Z', effectiveEnd: '2027-11-30T04:00:00Z' })])
  })

  await test.step('VS-C4 AC9, AC10: neither booking was changed', async () => {
    expect((await bookingsOf(request, first.eventId)).find((b) => b.bookingId === first.bookingId)!.status).toBe('approved')
    expect((await bookingsOf(request, second.eventId)).find((b) => b.bookingId === second.bookingId)!.status).toBe('approved')
    await staff.reload()
    await expect(staff.getByRole('region', { name: 'Existing timing conflicts' })).toHaveCount(0)
  })
})
