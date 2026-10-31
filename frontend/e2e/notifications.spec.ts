// @stories EC-NEW4 EO-C1 ELC-C6 VS-C1
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { apiToken, confirmedEventByApi, createVenue, evidence, leadAssigns, requestIdOf, signedIn, uniqueName } from './support.ts'

// IS212 Week 9: the notifications each role gets, through the real bell in the
// browser. Expected wording comes from the EC-NEW4 and EO-C1 ACs.

async function openBell(page: Page) {
  await page.getByRole('button', { name: /^Notifications/ }).click()
  return page.locator('.notification-bell__panel')
}

async function submittedByApi(request: APIRequestContext, eventName: string) {
  const token = await apiToken(request, 'eo1')
  const headers = { Authorization: `Bearer ${token}` }
  const draft = await request.post('/api/event-requests', { headers, data: { eventName, purpose: 'Notifications journey',
    startDatetime: '2027-08-12T09:00:00+08:00', endDatetime: '2027-08-12T12:00:00+08:00', expectedAttendance: 150,
    venueRequirements: 'Theatre seating', accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false } })
  expect(draft.ok(), await draft.text()).toBeTruthy()
  const submitted = await request.post(`/api/event-requests/${(await draft.json()).requestId}/submit`, { headers })
  expect(submitted.ok(), await submitted.text()).toBeTruthy()
}

test('EC-NEW4 (happy): the Lead is told of a new request, the coordinator of its assignment, and both can act from the bell', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Notice Gala')
  await submittedByApi(request, eventName)
  const requestId = await requestIdOf(request, eventName)

  const lead = await signedIn(browser, 'ecl1')
  await test.step('EC-NEW4 AC16, AC17, AC18: the Lead sees it waiting, from whom, and when in Singapore time', async () => {
    const item = (await openBell(lead)).getByRole('listitem').filter({ hasText: eventName })
    await expect(item.getByText(`New request "${eventName}" is waiting to be assigned`)).toBeVisible()
    await expect(item.getByText(/Acme Pte Ltd submitted a request for 12 Aug 2027, 9:00 AM/)).toBeVisible()
    await expect(item.getByText('By eo1 (Event Organiser)')).toBeVisible()
    await expect(item.locator('.notification-bell__item-time')).toContainText('(Singapore time)')
    await evidence(lead, 'EC-NEW4-TC1a')
  })

  await test.step('ELC-C6: the Lead assigns it to ec1', async () => {
    await leadAssigns(lead, requestId)
  })

  const coordinator = await signedIn(browser, 'ec1')
  const panel = await openBell(coordinator)
  const item = panel.getByRole('listitem').filter({ hasText: eventName })
  await test.step('EC-NEW4 AC2, AC17, AC24: the coordinator is told it is theirs, with the organisation, dates and who assigned it; it is marked Unread in text', async () => {
    await expect(item.getByText(`"${eventName}" was assigned to you`)).toBeVisible()
    await expect(item.getByText(/^Assigned to you: Acme Pte Ltd, 12 Aug 2027, 9:00 AM to 12 Aug 2027, 12:00 PM \(Singapore time\), 150 expected\.$/)).toBeVisible()
    await expect(item.getByText('By ecl1 (Event Coordinator Lead)')).toBeVisible()
    await expect(item.getByText('Unread')).toBeVisible()
    await evidence(coordinator, 'EC-NEW4-TC1b')
  })

  await test.step('EC-NEW4 AC25: Mark all as read clears the Unread marker', async () => {
    await panel.getByRole('button', { name: 'Mark all as read' }).click()
    await expect(item.getByText('Unread')).toHaveCount(0)
    await evidence(coordinator, 'EC-NEW4-TC5')
  })

  await test.step('EC-NEW4 AC19: the notice opens the request, where the coordinator acts on it', async () => {
    await item.getByRole('button').click()
    await expect(coordinator).toHaveURL(new RegExp(`/coordinator/requests/${requestId}$`))
  })
})

test('EO-C1 (happy): the organiser of a confirmed event is told at once when its venue becomes unavailable; another organisation is not', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('E2E Confirmed Gala')
  const venueName = uniqueName('E2E Harbour Hall')
  const venueId = await createVenue(request, venueName)
  const eventId = await confirmedEventByApi(request, eventName, venueId, '2027-08-19T09:00:00+08:00', '2027-08-19T12:00:00+08:00')

  await test.step('VS-C1: Venue Staff record the venue unavailable over the event, acknowledging the booking', async () => {
    const headers = { Authorization: `Bearer ${await apiToken(request, 'vs1')}` }
    const period = { startDateTime: '2027-08-19T08:00:00+08:00', endDateTime: '2027-08-19T13:00:00+08:00', reason: 'Flooding' }
    const preview = await request.post(`/api/venues/${venueId}/unavailability/preview`, { headers, data: period })
    expect(preview.ok(), await preview.text()).toBeTruthy()
    const { affectedBookings, previewToken } = await preview.json()
    const saved = await request.post(`/api/venues/${venueId}/unavailability`, { headers, data: { ...period, previewToken,
      acknowledgedBookingIds: affectedBookings.map((b: { bookingId: string }) => b.bookingId) } })
    expect(saved.ok(), await saved.text()).toBeTruthy()
  })

  const organiser = await signedIn(browser, 'eo1')
  const item = (await openBell(organiser)).getByRole('listitem')
    .filter({ hasText: `Venue update for your confirmed event "${eventName}"` })
  await test.step('EO-C1 AC1, AC6, AC7: the organiser sees what changed, the venue, when in Singapore time, the reason and what happens next', async () => {
    await expect(item.getByText(`Venue update for your confirmed event "${eventName}"`)).toBeVisible()
    const message = item.getByText(/^A venue for your event is unavailable from 19 Aug 2027, 8:00 AM to 19 Aug 2027, 1:00 PM \(Singapore time\)\./)
    await expect(message).toContainText(`Affected venue: ${venueName}`)
    await expect(message).toContainText('Reason: Flooding.')
    await expect(message).toContainText('being re-confirmed')
    await evidence(organiser, 'EO-C1-TC1a')
  })

  await test.step('EO-C1 AC15: it opens the event\'s arrangements', async () => {
    await item.getByRole('button').click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/events/${eventId}$`))
    await evidence(organiser, 'EO-C1-TC1b')
  })

  await test.step('EO-C1 AC16, AC17: another organisation is not told, and cannot open the event by API', async () => {
    const outsider = await signedIn(browser, 'eo3')
    await expect((await openBell(outsider)).getByRole('listitem').filter({ hasText: eventName })).toHaveCount(0)
    await evidence(outsider, 'EO-C1-TC4')
    const opened = await request.get(`/api/events/${eventId}`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'eo3')}` } })
    expect(opened.ok()).toBeFalsy()
  })
})
