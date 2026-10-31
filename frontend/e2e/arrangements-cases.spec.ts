// @stories EO04 EC08 EO07 VS-C1 EC10
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiGet, apiToken, approvedBookingByApi, approvedEquipmentByApi, cancelledByApi, confirmByApi, createVenue, openBell,
  planningEventWithNeeds, safetyApprovedByApi, signedIn, uniqueDay, uniqueName, venueUnavailableByApi,
} from './support.ts'

// Sprint 3 functional test cases for EO04 (Sheet: Test Cases - Sprint 3,
// EO04-TC1…TC25): the organiser views an event's confirmed arrangements.
// Fixtures through the API; what the organiser sees, in the browser.
// EO04-TC21 (Completed keeps its arrangements) needs an event whose end has
// passed, which the server will not create; backend tests cover it with a clock.

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

/** A confirmed event of eo1 with an approved venue and approved equipment, on a run-unique day. */
async function confirmedWithEquipment(request: APIRequestContext, prefix: string,
  needs: Parameters<typeof planningEventWithNeeds>[4] = {}) {
  const day = uniqueDay()
  const eventName = uniqueName(prefix)
  const venueName = uniqueName('Marina Grand Ballroom')
  const venueId = await createVenue(request, venueName, 400)
  const { eventId, requestId } = await planningEventWithNeeds(request, eventName, `${day}T09:00:00+08:00`,
    `${day}T17:00:00+08:00`, { equipmentRequirements: 'Two projection screens', ...needs })
  const bookingId = await approvedBookingByApi(request, eventId, venueId)
  await approvedEquipmentByApi(request, eventId, `${day}T01:00:00Z`, `${day}T09:00:00Z`)
  await safetyApprovedByApi(request, eventId)
  await confirmByApi(request, eventId)
  return { eventId, requestId, eventName, venueName, venueId, bookingId, day }
}

test('EO04 (happy): TC1, TC5, TC6, TC9, TC11, TC13, TC15 the organiser opens a confirmed event from the list and from the notification, and sees every arrangement in Singapore time', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Annual Conference')
  const organiser = await signedIn(browser, 'eo1')
  await test.step('TC5: View event on the request list opens the arrangements', async () => {
    await organiser.goto('/organiser')
    await organiser.getByRole('listitem').filter({ has: organiser.getByRole('heading', { name: e.eventName }) })
      .getByRole('link', { name: 'View event' }).click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/events/${e.eventId}$`))
  })
  await test.step('TC1, TC9, TC11, TC13, TC15: status, times labelled Singapore, venue details, equipment, safety and confirmation dates, contact', async () => {
    await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
    await expect(organiser.getByText('Start (Singapore time)')).toBeVisible()
    const venues = organiser.getByRole('region', { name: 'Venues' })
    await expect(venues).toContainText(e.venueName)
    for (const label of ['Capacity', 'Room layout', 'Facilities', 'Accessibility at this venue', 'Operating hours']) {
      await expect(venues.getByText(label, { exact: true })).toBeVisible()
    }
    await expect(venues).toContainText('400')
    await expect(organiser.getByRole('region', { name: 'Equipment' })).toContainText(/Projection Screen.*2|2.*Projection Screen/)
    await expect(organiser.getByText('Safety check approved')).toBeVisible()
    await expect(organiser.getByText('Questions?')).toBeVisible()
  })
  await test.step('TC6: the confirmation notification opens the same page', async () => {
    await organiser.goto('/organiser')
    await (await openBell(organiser)).getByRole('listitem').filter({ hasText: `"${e.eventName}" is now Confirmed` })
      .getByRole('button').click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/events/${e.eventId}$`))
  })
})

test('EO04 (cross-cutting): TC2, TC3, TC4, TC25 a colleague in the same organisation can view; another organisation cannot tell it exists; other roles and signed-out visitors are refused', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Shared Conference')
  const colleague = await signedIn(browser, 'eo2')
  await colleague.goto(`/organiser/events/${e.eventId}`)
  await expect(colleague.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()

  const theirs = await request.get(`/api/events/${e.eventId}/arrangements`, { headers: await auth(request, 'eo3') })
  const nobody = await request.get('/api/events/00000000-0000-4000-8000-000000000000/arrangements', { headers: await auth(request, 'eo3') })
  expect(theirs.status()).toBe(404)
  expect(nobody.status()).toBe(404)
  const outsider = await signedIn(browser, 'eo3')
  await outsider.goto(`/organiser/events/${e.eventId}`)
  await expect(outsider.getByRole('heading', { name: 'Confirmed arrangements' })).toHaveCount(0)

  for (const username of ['ec1', 'ecl1', 'vs1', 'ts1', 'so1']) {
    expect((await request.get(`/api/events/${e.eventId}/arrangements`, { headers: await auth(request, username) })).status(), username).toBe(403)
  }
  expect((await request.get(`/api/events/${e.eventId}/arrangements`)).status()).toBe(401)
  const visitor = await browser.newPage()
  await visitor.goto(`/organiser/events/${e.eventId}`)
  await expect(visitor.getByLabel('Username')).toBeVisible()
})

test('EO04 (boundary): TC7, TC8, TC10 before confirmation, even when Safety Approved, no venue or equipment details are shown; both dates of a two-day event are', async ({ browser, request }) => {
  const day = uniqueDay()
  const venueId = await createVenue(request, uniqueName('Acme Approval Hall'))
  const planning = await planningEventWithNeeds(request, uniqueName('Acme Product Launch'), `${day}T09:00:00+08:00`,
    '2029-12-31T17:00:00+08:00')
  const approved = await planningEventWithNeeds(request, uniqueName('Acme Ready To Confirm Night'), `${day}T18:00:00+08:00`,
    `${day}T21:00:00+08:00`)
  await approvedBookingByApi(request, approved.eventId, venueId)
  await safetyApprovedByApi(request, approved.eventId)

  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${planning.eventId}`)
  await expect(organiser.getByText('Planning', { exact: true }).first()).toBeVisible()
  await expect(organiser.getByText('Event Coordinator', { exact: true })).toBeVisible()
  await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toHaveCount(0)
  await expect(organiser.getByRole('region', { name: 'Venues' })).toHaveCount(0)
  await expect(organiser.getByText(/31 Dec 2029/).first()).toBeVisible()

  await organiser.goto(`/organiser/events/${approved.eventId}`)
  await expect(organiser.getByText('Safety Approved', { exact: true }).first()).toBeVisible()
  await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toHaveCount(0)
  await expect(organiser.getByRole('region', { name: 'Venues' })).toHaveCount(0)
})

test('EO04 (happy): TC12, TC14, TC16 accessibility needs are matched against the venue; no equipment says so; internal notes and rejected venues are hidden, on the page and in the API', async ({ browser, request }) => {
  const day = uniqueDay()
  const rejectedVenue = uniqueName('Acme Rejected Hall')
  const rejectedId = await createVenue(request, rejectedVenue)
  const venueName = uniqueName('Acme Riverside Hall')
  const venueId = await createVenue(request, venueName)
  const { eventId } = await planningEventWithNeeds(request, uniqueName('Acme Beach Picnic'), `${day}T09:00:00+08:00`,
    `${day}T12:00:00+08:00`, { accessibilityNeeds: ['step_free_access', 'wheelchair_support'] })
  const ec = await auth(request, 'ec1')
  const first = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: ec,
    data: { venueId: rejectedId, bookingNotes: 'INTERNAL NOTE for venue staff', suitabilityNote: 'INTERNAL JUSTIFICATION' } })
  expect(first.ok(), await first.text()).toBeTruthy()
  const firstId = (await first.json()).bookingId as string
  expect((await request.patch(`/api/venue-bookings/${firstId}/reject`, { headers: await auth(request, 'vs1'),
    data: { reason: 'INTERNAL REJECTION REASON', alternativeVenueId: null, alternativeArrangement: null } })).ok()).toBeTruthy()
  const second = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: ec,
    data: { venueId, replacesBookingId: firstId, bookingNotes: 'INTERNAL NOTE two', suitabilityNote: 'INTERNAL JUSTIFICATION two' } })
  expect(second.ok(), await second.text()).toBeTruthy()
  expect((await request.patch(`/api/venue-bookings/${(await second.json()).bookingId}/approve`, { headers: await auth(request, 'vs1') })).ok()).toBeTruthy()
  await safetyApprovedByApi(request, eventId)
  await confirmByApi(request, eventId)

  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${eventId}`)
  const venues = organiser.getByRole('region', { name: 'Venues' })
  await expect(venues.getByText('Your accessibility needs')).toBeVisible()
  await expect(venues.getByText('Not listed for this venue. Check with your coordinator how it will be handled.')).toBeVisible()
  await expect(organiser.getByText('No equipment was arranged for this event.')).toBeVisible()
  const page = await organiser.content()
  for (const hidden of ['INTERNAL', rejectedVenue]) expect(page).not.toContain(hidden)
  const json = JSON.stringify(await apiGet(request, await apiToken(request, 'eo1'), `/api/events/${eventId}/arrangements`))
  for (const hidden of ['INTERNAL', rejectedVenue]) expect(json).not.toContain(hidden)
})

test('EO04 (conflict): TC17, TC18, TC19 the venue becomes unavailable after confirmation, then is replaced and the event confirmed again', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Riverside Concert')
  await venueUnavailableByApi(request, e.venueId, `${e.day}T08:00:00+08:00`, `${e.day}T18:00:00+08:00`, 'Flooding')
  const organiser = await signedIn(browser, 'eo1')
  await test.step('TC17, TC18: back in Planning; the venue is marked no longer available; the equipment stays listed', async () => {
    await organiser.goto(`/organiser/events/${e.eventId}`)
    await expect(organiser.getByText('Arrangements are being updated.')).toBeVisible()
    await expect(organiser.getByText('No longer available')).toBeVisible()
    await expect(organiser.getByRole('region', { name: 'Equipment' })).toContainText('Projection Screen')
  })
  await test.step('TC19: after a replacement is approved and the event confirmed again, the new venue shows as updated', async () => {
    const replacementName = uniqueName('Acme Replacement Hall')
    const replacementId = await createVenue(request, replacementName, 400)
    const replacement = await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: await auth(request, 'ec1'),
      data: { venueId: replacementId, replacesBookingId: e.bookingId } })
    expect(replacement.ok(), await replacement.text()).toBeTruthy()
    expect((await request.patch(`/api/venue-bookings/${(await replacement.json()).bookingId}/approve`,
      { headers: await auth(request, 'vs1') })).ok()).toBeTruthy()
    await safetyApprovedByApi(request, e.eventId)
    await confirmByApi(request, e.eventId)
    await organiser.reload()
    const venues = organiser.getByRole('region', { name: 'Venues' })
    await expect(venues).toContainText(replacementName)
    await expect(venues).not.toContainText(e.venueName)
    await expect(organiser.getByText('Updated since', { exact: false }).first()).toBeVisible()
  })
})

test('EO04 (negative): TC20 a cancelled event says there are no arrangements to show', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Cancelled Mixer')
  await cancelledByApi(request, e.eventId, e.requestId)
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${e.eventId}`)
  await expect(organiser.getByText('This event was cancelled, so there are no arrangements to show.')).toBeVisible()
  await expect(organiser.getByRole('region', { name: 'Venues' })).toHaveCount(0)
})

test('EO04 (cross-cutting): TC22, TC23 printing shows only the arrangements; the page fits phones and tablets without sideways scrolling', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Printable Conference')
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${e.eventId}`)
  await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
  await organiser.emulateMedia({ media: 'print' })
  await expect(organiser.getByRole('button', { name: 'Log out' })).toBeHidden()
  await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
  await organiser.emulateMedia({ media: 'screen' })
  for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 1024 }]) {
    await organiser.setViewportSize(viewport)
    await organiser.reload()
    await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
    const overflow = await organiser.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow, `${viewport.width}px`).toBeLessThanOrEqual(0)
  }
})

test('EO04 (failure): TC24 if the arrangements cannot be loaded the organiser is told, and Try again loads them', async ({ browser, request }) => {
  const e = await confirmedWithEquipment(request, 'Acme Offline Conference')
  const organiser = await signedIn(browser, 'eo1')
  await organiser.route(`**/api/events/${e.eventId}/arrangements`, (route) => route.abort())
  await organiser.goto(`/organiser/events/${e.eventId}`)
  await expect(organiser.getByText('We could not load your event arrangements. Check your connection and try again.')).toBeVisible()
  await organiser.unroute(`**/api/events/${e.eventId}/arrangements`)
  await organiser.getByRole('button', { name: 'Try again' }).click()
  await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
})
