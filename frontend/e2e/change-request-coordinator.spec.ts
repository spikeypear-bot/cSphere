// @stories EC-NEW3 EO03 EC-NEW4 ECL-C7 EC-NEW2
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiGet, apiToken, approvedBookingByApi, changeRequestByApi, confirmedEventByApi, createVenue, evidence, openBell,
  planningEventByApi, requestOfEvent, signedIn, uniqueName,
} from './support.ts'

// EC-NEW3 functional test cases (Sheet: Test Cases - Sprint 4, EC-NEW3-TC1…TC7),
// in the browser against the real backend. The coordinator's decisions are
// browser actions; the event, its bookings and the organiser's change request
// are fixtures. Expected wording comes from the EC-NEW3 and EO03 ACs.

type Event = { expectedAttendance: number; endDatetime: string; journeyStatus: { code: string } }

async function eventOf(request: APIRequestContext, eventId: string): Promise<Event> {
  return apiGet<Event>(request, await apiToken(request, 'eo1'), `/api/events/${eventId}`)
}

async function withdrawByApi(request: APIRequestContext, changeId: string) {
  const result = await request.post(`/api/change-requests/${changeId}/withdraw`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` } })
  expect(result.ok(), `withdraw: ${result.status()} ${await result.text()}`).toBeTruthy()
}

test('EC-NEW3 AC5, AC6, AC8, AC26, AC31, AC34, AC36, AC41 (happy): the coordinator compares current and proposed, acknowledges every item that needs attention, approves, and the confirmed event returns to Planning', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('EC-NEW3 Approve Gala')
  const venueName = uniqueName('EC-NEW3 Hall')
  const venueId = await createVenue(request, venueName)
  const eventId = await confirmedEventByApi(request, eventName, venueId, '2027-11-02T09:00:00+08:00', '2027-11-02T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const changeId = await changeRequestByApi(request, requestId,
    { expectedAttendance: 140, endDatetime: '2027-11-02T13:00:00+08:00' }, 'Two more departments are joining')
  const coordinator = await signedIn(browser, 'ec1')

  await test.step('AC5, AC6, AC8: current and proposed side by side; the venue booked for the old time and the return to Planning need attention', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByRole('region', { name: 'Change requested' })).toBeVisible()
    await coordinator.getByRole('link', { name: 'Review and decide' }).click()
    await expect(coordinator.getByRole('row', { name: /Expected attendance/ })).toContainText('140')
    await expect(coordinator.getByText(`${venueName} is booked for the old time`)).toBeVisible()
    await expect(coordinator.getByText('The event goes back to Planning')).toBeVisible()
    await expect(coordinator.getByText('Two more departments are joining')).toBeVisible()
    await evidence(coordinator, 'EC-NEW3-TC1a')
  })

  await test.step('AC26: Approve stays unavailable until every Needs-attention item is acknowledged', async () => {
    const approve = coordinator.getByRole('button', { name: 'Approve and apply the change' })
    await expect(approve).toBeDisabled()
    await expect(coordinator.getByText('Tick each item that needs attention to approve.')).toBeVisible()
    for (const box of await coordinator.getByRole('checkbox', { name: 'I have considered this' }).all()) await box.check()
    await expect(approve).toBeEnabled()
    await approve.click()
    await expect(coordinator.getByText('Change approved and applied. The organiser has been told.')).toBeVisible()
    await expect(coordinator.getByText('Still to follow up')).toBeVisible()
    await evidence(coordinator, 'EC-NEW3-TC1b')
  })

  await test.step('AC34, AC36: every change is applied together and the event is back in Planning; the booking is not moved', async () => {
    const event = await eventOf(request, eventId)
    expect(event.expectedAttendance).toBe(140)
    expect(Date.parse(event.endDatetime)).toBe(Date.parse('2027-11-02T13:00:00+08:00'))
    expect(event.journeyStatus.code).toBe('PLANNING')
  })

  await test.step('AC31: deciding the same request again is refused', async () => {
    const again = await request.post(`/api/change-requests/${changeId}/approve`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { acknowledged: [] } })
    expect(again.ok()).toBeFalsy()
  })

  await test.step('AC41, EO03 AC37, AC38: the organiser is told and sees the new details with the decision', async () => {
    const organiser = await signedIn(browser, 'eo1')
    const item = (await openBell(organiser)).getByRole('listitem')
      .filter({ hasText: `Decision on your change request for "${eventName}"` })
    await expect(item).toContainText('Your change request was approved: End: 2 Nov 2027, 12:00 PM → 2 Nov 2027, 1:00 PM; Expected attendance: 120 → 140.')
    await expect(item.first()).toBeVisible()
    await evidence(organiser, 'EO03-TC8a')
    await item.first().getByRole('button').click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/requests/${requestId}/changes$`))
    await expect(organiser.getByText(/Approved by ec1/)).toBeVisible()
    await evidence(organiser, 'EO03-TC8b')
  })
})

test('EC-NEW3 AC24, AC25, AC38, AC41 (negative, boundary): rejecting needs a reason of 1 to 2,000 characters; the event is unchanged and the organiser sees why', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EC-NEW3 Reject Gala')
  const eventId = await planningEventByApi(request, eventName, '2027-11-03T09:00:00+08:00', '2027-11-03T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const changeId = await changeRequestByApi(request, requestId, { expectedAttendance: 400 }, 'Opening it to the public')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/change-requests/${changeId}`)
  await coordinator.getByRole('button', { name: 'Reject…' }).click()
  const reason = coordinator.getByLabel("Why can't this change be made?")
  const reject = coordinator.getByRole('button', { name: 'Reject the change' })

  await test.step('AC25: blank, spaces-only and 2,001-character reasons are refused', async () => {
    await expect(reject).toBeDisabled()
    await reason.fill('   ')
    await expect(reject).toBeDisabled()
    await reason.fill('z'.repeat(2001))
    await expect(coordinator.getByText('Keep it to 2000 characters.')).toBeVisible()
    await expect(reject).toBeDisabled()
    await evidence(coordinator, 'EC-NEW3-TC2a')
  })

  await test.step('AC38: rejected with a reason; details, status and arrangements stay as they were', async () => {
    await reason.fill('No venue we use holds 400 people on that date.')
    await reject.click()
    await expect(coordinator.getByText('Change rejected. The organiser has been told why.')).toBeVisible()
    await expect(coordinator.getByText(/Rejected by ec1/)).toBeVisible()
    await evidence(coordinator, 'EC-NEW3-TC2b')
    const event = await eventOf(request, eventId)
    expect(event.expectedAttendance).toBe(150)
    expect(event.journeyStatus.code).toBe('PLANNING')
  })

  await test.step('AC41, EO03 AC37, AC38: the organiser sees the outcome and the reason', async () => {
    const organiser = await signedIn(browser, 'eo1')
    await organiser.goto(`/organiser/requests/${requestId}/changes`)
    await expect(organiser.getByText(/Rejected by ec1/)).toBeVisible()
    await expect(organiser.getByText('No venue we use holds 400 people on that date.')).toBeVisible()
    await evidence(organiser, 'EO03-TC8c')
  })
})

test('EC-NEW3 AC9, AC10, AC12 (conflict): extending the event into another event\'s booking at the same venue is flagged, without naming the other event', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const venueName = uniqueName('EC-NEW3 Clash Hall')
  const venueId = await createVenue(request, venueName)
  const morningId = await planningEventByApi(request, uniqueName('EC-NEW3 Morning Gala'), '2027-11-04T09:00:00+08:00', '2027-11-04T12:00:00+08:00')
  await approvedBookingByApi(request, morningId, venueId)
  const afternoonName = uniqueName('EC-NEW3 Afternoon Gala')
  const afternoonId = await planningEventByApi(request, afternoonName, '2027-11-04T15:00:00+08:00', '2027-11-04T17:00:00+08:00')
  await approvedBookingByApi(request, afternoonId, venueId)
  const changeId = await changeRequestByApi(request, await requestOfEvent(request, morningId),
    { endDatetime: '2027-11-04T16:00:00+08:00' }, 'The panel runs longer')

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/change-requests/${changeId}`)
  const venues = coordinator.getByRole('region', { name: 'Venues' })
  await expect(venues.getByText(`${venueName} is booked by another event then`)).toBeVisible()
  await expect(venues).toContainText('Needs attention')
  await expect(venues).not.toContainText(afternoonName)
  await evidence(coordinator, 'EC-NEW3-TC3')
})

test('EC-NEW3 AC17 (boundary): attendance equal to the booked venue\'s capacity fits; one more does not', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const venueName = uniqueName('EC-NEW3 Capacity Hall')
  const venueId = await createVenue(request, venueName, 160)
  const eventId = await planningEventByApi(request, uniqueName('EC-NEW3 Capacity Gala'), '2027-11-05T09:00:00+08:00', '2027-11-05T12:00:00+08:00')
  await approvedBookingByApi(request, eventId, venueId)
  const requestId = await requestOfEvent(request, eventId)
  const coordinator = await signedIn(browser, 'ec1')

  await test.step('160 people in a 160-seat venue: it still fits', async () => {
    const equal = await changeRequestByApi(request, requestId, { expectedAttendance: 160 })
    await coordinator.goto(`/coordinator/change-requests/${equal}`)
    await expect(coordinator.getByText(`${venueName} still fits`)).toBeVisible()
    await expect(coordinator.getByText(`${venueName} is too small`)).toHaveCount(0)
    await evidence(coordinator, 'EC-NEW3-TC4a')
    await withdrawByApi(request, equal)
  })

  await test.step('161 people: the venue is too small and needs attention', async () => {
    const over = await changeRequestByApi(request, requestId, { expectedAttendance: 161 })
    await coordinator.goto(`/coordinator/change-requests/${over}`)
    await expect(coordinator.getByText(`${venueName} is too small`)).toBeVisible()
    await expect(coordinator.getByText('It holds 160; 161 are expected.')).toBeVisible()
    await evidence(coordinator, 'EC-NEW3-TC4b')
  })
})

test('EC-NEW3 AC1, AC2 (negative): another coordinator is refused, in the page and by API; the Lead can view the change but not decide it', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventId = await planningEventByApi(request, uniqueName('EC-NEW3 Access Gala'), '2027-11-06T09:00:00+08:00', '2027-11-06T12:00:00+08:00')
  const changeId = await changeRequestByApi(request, await requestOfEvent(request, eventId), { expectedAttendance: 120 })

  await test.step('AC1: ec2 (not assigned) sees the not-assigned notice and cannot approve by API', async () => {
    const other = await signedIn(browser, 'ec2')
    await other.goto(`/coordinator/change-requests/${changeId}`)
    await expect(other.getByRole('heading', { name: "This request isn't assigned to you" })).toBeVisible()
    await evidence(other, 'EC-NEW3-TC5a')
    const refused = await request.post(`/api/change-requests/${changeId}/approve`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec2')}` }, data: { acknowledged: [] } })
    expect(refused.ok()).toBeFalsy()
    expect((await eventOf(request, eventId)).expectedAttendance).toBe(150)
  })

  await test.step('AC2: the Lead sees the change and its impact, read-only', async () => {
    const lead = await signedIn(browser, 'ecl1')
    await lead.goto(`/coordinator-lead/change-requests/${changeId}`)
    await expect(lead.getByText("You can see this change and its impact; only the event's coordinator can decide it.")).toBeVisible()
    await expect(lead.getByText('What this change would affect')).toBeVisible()
    await expect(lead.getByRole('button', { name: 'Approve and apply the change' })).toHaveCount(0)
    await evidence(lead, 'EC-NEW3-TC5b')
  })
})

test('EC-NEW3 AC3, AC4 (happy): the coordinator sees how many changes are waiting on their events list and which event each is for', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EC-NEW3 Waiting Gala')
  const eventId = await planningEventByApi(request, eventName, '2027-11-07T09:00:00+08:00', '2027-11-07T12:00:00+08:00')
  await changeRequestByApi(request, await requestOfEvent(request, eventId), { expectedAttendance: 130 }, 'Fewer guests')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/my-events')
  await expect(coordinator.getByText(/\d+ change requests? (is|are) waiting for your decision\./)).toBeVisible()
  const card = coordinator.getByRole('listitem').filter({ has: coordinator.getByRole('heading', { name: eventName }) })
  await expect(card.getByRole('link', { name: 'Change requested · review' })).toBeVisible()
  const waiting = coordinator.getByRole('region', { name: 'Change requests waiting for you' })
  const entry = waiting.getByRole('listitem').filter({ has: coordinator.getByRole('link', { name: `Change to ${eventName}` }) })
  await expect(entry).toContainText('Acme Pte Ltd')
  await expect(entry).toContainText('Fewer guests')
  await expect(entry).toContainText(/\(Singapore time\) · (just now|.+ ago)/)
  await evidence(coordinator, 'EC-NEW3-TC6')
})

test('EC-NEW3 AC30 (conflict): a decision on a change the organiser has just withdrawn is refused and the latest state is shown', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventId = await planningEventByApi(request, uniqueName('EC-NEW3 Stale Gala'), '2027-11-08T09:00:00+08:00', '2027-11-08T12:00:00+08:00')
  const changeId = await changeRequestByApi(request, await requestOfEvent(request, eventId), { expectedAttendance: 110 })
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/change-requests/${changeId}`)
  await expect(coordinator.getByRole('button', { name: 'Approve and apply the change' })).toBeVisible()

  await withdrawByApi(request, changeId)
  for (const box of await coordinator.getByRole('checkbox', { name: 'I have considered this' }).all()) await box.check()
  await coordinator.getByRole('button', { name: 'Approve and apply the change' }).click()

  await expect(coordinator.getByText('Withdrawn', { exact: true })).toBeVisible()
  await expect(coordinator.getByRole('button', { name: 'Approve and apply the change' })).toHaveCount(0)
  await evidence(coordinator, 'EC-NEW3-TC7')
  expect((await eventOf(request, eventId)).expectedAttendance).toBe(150)
})
