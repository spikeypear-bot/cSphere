// @stories EC-NEW4 EO-C1 EC03 VS04 VS-C1 EC08
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiGet, apiToken, approvedBookingByApi, confirmedEventByApi, createVenue, evidence, openBell, planningEventByApi,
  signedIn, uniqueName,
} from './support.ts'

// EC-NEW4 and EO-C1 functional test cases not covered by notifications.spec.ts
// (Sheet: Test Cases - Sprint 4, EC-NEW4-TC3, TC4, TC6 and EO-C1-TC2, TC3).
// Expected wording comes from the EC-NEW4 and EO-C1 ACs.

type Notice = { notificationId: string; eventName: string | null; type: string }

/** VS-C1 fixture: vs1 records the venue unavailable over a period, acknowledging every booking it affects. */
async function venueUnavailable(request: APIRequestContext, venueId: string, start: string, end: string, reason: string) {
  const headers = { Authorization: `Bearer ${await apiToken(request, 'vs1')}` }
  const period = { startDateTime: start, endDateTime: end, reason }
  const preview = await request.post(`/api/venues/${venueId}/unavailability/preview`, { headers, data: period })
  expect(preview.ok(), await preview.text()).toBeTruthy()
  const { affectedBookings, previewToken } = await preview.json()
  const saved = await request.post(`/api/venues/${venueId}/unavailability`, { headers, data: { ...period, previewToken,
    acknowledgedBookingIds: affectedBookings.map((b: { bookingId: string }) => b.bookingId) } })
  expect(saved.ok(), await saved.text()).toBeTruthy()
}

test('EC-NEW4 AC9, AC17, AC19 (happy): Venue Staff reject a booking with a reason; the coordinator is told why and the notice opens the booking', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EC-NEW4 Rejected Gala')
  const venueId = await createVenue(request, uniqueName('EC-NEW4 Hall'))
  const eventId = await planningEventByApi(request, eventName, '2027-12-08T09:00:00+08:00', '2027-12-08T12:00:00+08:00')
  const booking = await request.post(`/api/events/${eventId}/venue-bookings`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { venueId } })
  expect(booking.ok(), await booking.text()).toBeTruthy()

  const staff = await signedIn(browser, 'vs1')
  await test.step('VS04: Venue Staff reject it in the browser, with a reason', async () => {
    const rejected = await request.patch(`/api/venue-bookings/${(await booking.json()).bookingId}/reject`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'vs1')}` },
      data: { reason: 'The hall is being repainted that week.', alternativeVenueId: null, alternativeArrangement: null } })
    expect(rejected.ok(), await rejected.text()).toBeTruthy()
    await staff.close()
  })

  const coordinator = await signedIn(browser, 'ec1')
  const item = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: `Venue booking for "${eventName}" was rejected` })
  await test.step('AC9, AC17: the notice says what happened, to which event, who did it and why', async () => {
    await expect(item).toBeVisible()
    await expect(item).toContainText('The hall is being repainted that week.')
    await expect(item).toContainText('By vs1 (Venue Staff)')
    await evidence(coordinator, 'EC-NEW4-TC3a')
  })

  await test.step('AC19: it opens the event\'s venue booking, where the coordinator acts on it', async () => {
    await item.getByRole('button').click()
    await expect(coordinator).toHaveURL(new RegExp(`/coordinator/events/${eventId}`))
    await evidence(coordinator, 'EC-NEW4-TC3b')
  })
})

test('EC-NEW4 AC1, AC15 (negative): a coordinator is not told of their own action and cannot read or mark another user\'s notifications', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EC-NEW4 Own Gala')
  await planningEventByApi(request, eventName, '2027-12-09T09:00:00+08:00', '2027-12-09T12:00:00+08:00')

  await test.step('AC15: ec1 approved the request, so ec1 is not told it is now in Planning; the organiser is', async () => {
    const coordinator = await signedIn(browser, 'ec1')
    const panel = await openBell(coordinator)
    await expect(panel.getByRole('listitem').filter({ hasText: `"${eventName}" assigned to ec1` }).or(
      panel.getByRole('listitem').filter({ hasText: `"${eventName}" was assigned to you` })).first()).toBeVisible()
    await expect(panel.getByRole('listitem').filter({ hasText: `"${eventName}" is now in Planning` })).toHaveCount(0)
    await evidence(coordinator, 'EC-NEW4-TC4')
    const organiser = await signedIn(browser, 'eo1')
    await expect((await openBell(organiser)).getByRole('listitem')
      .filter({ hasText: `"${eventName}" is now in Planning` })).toBeVisible()
  })

  await test.step('AC1: ec2 cannot mark eo1\'s notification read; it is reported as not found and stays unread', async () => {
    const eo = await apiToken(request, 'eo1')
    const notice = (await apiGet<Notice[]>(request, eo, '/api/notifications')).find((n) => n.eventName === eventName)!
    const refused = await request.post(`/api/notifications/${notice.notificationId}/read`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec2')}` } })
    expect(refused.status()).toBe(404)
    const ec2List = await apiGet<Notice[]>(request, await apiToken(request, 'ec2'), '/api/notifications')
    expect(ec2List.some((n) => n.notificationId === notice.notificationId)).toBeFalsy()
  })
})

test('EC-NEW4 AC32 (failure): when notifications cannot be loaded the bell says so with Try again, and the page keeps working', async ({ browser }) => {
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.route('**/api/notifications', (route) => route.abort())
  await coordinator.goto('/coordinator/my-events')
  const panel = await openBell(coordinator)
  await expect(panel.getByText('Notifications could not be loaded.')).toBeVisible()
  await expect(coordinator.getByRole('heading', { name: 'My events', level: 1 })).toBeVisible()
  await evidence(coordinator, 'EC-NEW4-TC6a')
  await coordinator.unroute('**/api/notifications')
  await panel.getByRole('button', { name: 'Try again' }).click()
  await expect(panel.getByText('Notifications could not be loaded.')).toHaveCount(0)
  await expect(panel.getByRole('listitem').first()).toBeVisible()
  await evidence(coordinator, 'EC-NEW4-TC6b')
})

test('EO-C1 AC3 (negative): a venue becoming unavailable for an event that was never confirmed is internal; its organiser is not told', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EO-C1 Planning Gala')
  const venueId = await createVenue(request, uniqueName('EO-C1 Planning Hall'))
  const eventId = await planningEventByApi(request, eventName, '2027-12-10T09:00:00+08:00', '2027-12-10T12:00:00+08:00')
  await approvedBookingByApi(request, eventId, venueId)
  await venueUnavailable(request, venueId, '2027-12-10T08:00:00+08:00', '2027-12-10T13:00:00+08:00', 'Burst pipe')

  const coordinator = await signedIn(browser, 'ec1')
  await expect((await openBell(coordinator)).getByRole('listitem')
    .filter({ hasText: `A venue booking for "${eventName}" is unavailable` })).toBeVisible()
  const organiser = await signedIn(browser, 'eo1')
  const panel = await openBell(organiser)
  await expect(panel.getByRole('listitem').filter({ hasText: `Venue update for your confirmed event "${eventName}"` })).toHaveCount(0)
  await evidence(organiser, 'EO-C1-TC2')
})

test('EO-C1 AC7, AC14 (happy): after the venue becomes unavailable, a replacement is approved and the event confirmed again; the organiser is told the new venue', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('EO-C1 Replaced Gala')
  const firstVenue = uniqueName('EO-C1 First Hall')
  const secondVenue = uniqueName('EO-C1 Second Hall')
  const firstId = await createVenue(request, firstVenue)
  const secondId = await createVenue(request, secondVenue)
  const eventId = await confirmedEventByApi(request, eventName, firstId, '2027-12-11T09:00:00+08:00', '2027-12-11T12:00:00+08:00')
  await venueUnavailable(request, firstId, '2027-12-11T08:00:00+08:00', '2027-12-11T13:00:00+08:00', 'Roof repairs')

  const organiser = await signedIn(browser, 'eo1')
  await test.step('AC7: until a replacement is approved, the organiser is told the venue is not available and it is being re-confirmed', async () => {
    const item = (await openBell(organiser)).getByRole('listitem')
      .filter({ hasText: `Venue update for your confirmed event "${eventName}"` })
    await expect(item.first()).toContainText('being re-confirmed')
    await expect(item.first()).toContainText(firstVenue)
  })

  await test.step('fixture: a replacement venue is booked and approved, then the event passes its safety check and is confirmed again', async () => {
    const ec = { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }
    const bookings = await apiGet<{ bookingId: string; venueId: string }[]>(request, ec.Authorization.slice(7),
      `/api/events/${eventId}/venue-bookings`)
    const replaced = bookings.find((b) => b.venueId === firstId)!
    const replacement = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: ec,
      data: { venueId: secondId, replacesBookingId: replaced.bookingId } })
    expect(replacement.ok(), await replacement.text()).toBeTruthy()
    const approved = await request.patch(`/api/venue-bookings/${(await replacement.json()).bookingId}/approve`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'vs1')}` } })
    expect(approved.ok(), await approved.text()).toBeTruthy()
    const so = { Authorization: `Bearer ${await apiToken(request, 'so1')}` }
    const sent = await request.post(`/api/events/${eventId}/safety-review`, { headers: ec })
    expect(sent.ok(), await sent.text()).toBeTruthy()
    const readiness = await apiGet<{ items: { itemKey: string }[] }>(request, so.Authorization.slice(7),
      `/api/safety/events/${eventId}/readiness`)
    for (const item of readiness.items) {
      expect((await request.patch(`/api/safety/events/${eventId}/readiness/items/${item.itemKey}`,
        { headers: so, data: { checked: true } })).ok()).toBeTruthy()
    }
    expect((await request.post(`/api/safety/events/${eventId}/approval`, { headers: so, data: { decision: 'approve' } })).ok()).toBeTruthy()
    const confirmed = await request.post(`/api/events/${eventId}/confirm`, { headers: ec })
    expect(confirmed.ok(), await confirmed.text()).toBeTruthy()
  })

  await test.step('AC14: the organiser is told the event is confirmed again with the new venue', async () => {
    await organiser.reload()
    const item = (await openBell(organiser)).getByRole('listitem')
      .filter({ hasText: `Your event is confirmed again with this venue: ${secondVenue}` })
    await expect(item).toBeVisible()
    await evidence(organiser, 'EO-C1-TC3')
  })
})
