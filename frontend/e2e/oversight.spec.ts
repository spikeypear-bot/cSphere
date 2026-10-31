// @stories SO-C1 SO-C2 SO-C5 EC-C1 ECL-C4 EC-NEW4
import { expect, test } from '@playwright/test'
import { apiToken, awaitingSafetyReviewByApi, confirmedEventByApi, createVenue, openBell, planningEventByApi, signedIn, uniqueName } from './support.ts'

// Oversight roles: the Safety Officer's console and decisions, and the Lead
// moving a live event between coordinators at a coordinator's request.
// Expected wording comes from the SO-C1, SO-C2, SO-C5, EC-C1 and ECL-C4 ACs.
// SO-C5 AC7, AC8 and AC13 ("Safety Rejected" as its own, final state) are not
// asserted: they conflict with D30, where a rejection returns the event to
// the coordinator as Safety Changes Requested. Raised for the team to settle.

test('SO-C5 SO-C2 (happy): the Safety Officer rejects with a reason and the items concerned; the coordinator is told and sees why', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('E2E Safety Reject')
  const venueName = uniqueName('E2E Safety Hall')
  const venueId = await createVenue(request, venueName)
  const eventId = await awaitingSafetyReviewByApi(request, eventName, venueId, '2027-10-05T09:00:00+08:00', '2027-10-05T12:00:00+08:00')

  const officer = await signedIn(browser, 'so1')
  await test.step('SO-C1 AC7 / SO-C2 AC2, AC3, AC6: it is waiting for review, and opens with its details, its venue, and what is not finalised', async () => {
    await officer.goto('/safety-officer')
    const waiting = officer.getByRole('region', { name: 'Waiting for your safety review' })
      .or(officer.locator('section').filter({ has: officer.getByRole('heading', { name: 'Waiting for your safety review' }) }))
    const card = waiting.getByRole('listitem').filter({ hasText: eventName })
    await expect(card).toContainText(venueName)
    await card.getByRole('link', { name: 'View event' }).click()
    await expect(officer).toHaveURL(new RegExp(`/safety-officer/events/${eventId}$`))
    await expect(officer.getByText('E2E journey').first()).toBeVisible()
    await expect(officer.getByText(venueName).first()).toBeVisible()
    await expect(officer.getByText('No equipment request has been submitted.')).toBeVisible()
  })

  await test.step('SO-C5 AC3, AC5 (negative): Reject needs both a reason and at least one checklist item', async () => {
    const reject = officer.getByRole('button', { name: 'Reject', exact: true })
    await expect(reject).toBeDisabled()
    await officer.getByLabel('Note for the Event Coordinator').fill('Fire exits at the hall are blocked by staging.')
    await expect(reject).toBeDisabled()
    await officer.getByLabel('Note for the Event Coordinator').fill('   ')
    await officer.getByRole('checkbox', { name: 'Raise: Safety, emergency, and on-site operational arrangements are ready' }).check()
    await expect(reject).toBeDisabled()
  })

  await test.step('SO-C5 AC10, AC12: with both, the rejection is saved and recorded', async () => {
    await officer.getByLabel('Note for the Event Coordinator').fill('Fire exits at the hall are blocked by staging.')
    await officer.getByRole('button', { name: 'Reject', exact: true }).click()
    await expect(officer.getByText('Rejected').first()).toBeVisible()
    await expect(officer.getByText('Fire exits at the hall are blocked by staging.').first()).toBeVisible()
  })

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('SO-C5 AC10: the coordinator is told, with the reason', async () => {
    const item = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: eventName })
    await expect(item.first()).toContainText('The Safety Officer rejected the safety arrangement: Fire exits at the hall are blocked by staging.')
    await coordinator.keyboard.press('Escape')
  })

  await test.step('SO-C5 AC11: the coordinator\'s event page shows the rejection, by whom and when, and the item concerned', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    const decision = coordinator.locator('.safety-decision')
    await expect(decision).toContainText('Rejected')
    await expect(decision).toContainText('by so1')
    await expect(decision).toContainText('(Singapore time)')
    await expect(decision).toContainText('Fire exits at the hall are blocked by staging.')
    await expect(decision).toContainText('Safety, emergency, and on-site operational arrangements are ready')
  })

  await test.step('SO-C5 AC2, AC9 (negative): no one else can decide, and the same event cannot be decided twice', async () => {
    const body = { decision: 'reject', note: 'Again', flaggedItems: ['safety_operations'] }
    const asCoordinator = await request.post(`/api/safety/events/${eventId}/approval`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: body })
    expect(asCoordinator.status()).toBe(403)
    const twice = await request.post(`/api/safety/events/${eventId}/approval`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'so1')}` }, data: body })
    expect(twice.ok()).toBeFalsy()
  })
})

test('SO-C1 SO-C2 (happy): confirmed events are listed with date and venue, open read-only, and lead back to the list', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Safety Confirmed')
  const venueName = uniqueName('E2E Confirmed Hall')
  const eventId = await confirmedEventByApi(request, eventName, await createVenue(request, venueName),
    '2027-10-12T09:00:00+08:00', '2027-10-12T12:00:00+08:00')

  const officer = await signedIn(browser, 'so1')
  await test.step('SO-C1 AC1, AC2: the confirmed event is listed with its date in Singapore time and its venue', async () => {
    await officer.goto('/safety-officer')
    const confirmed = officer.locator('section').filter({ has: officer.getByRole('heading', { name: 'Confirmed events' }) })
    const card = confirmed.getByRole('listitem').filter({ hasText: eventName })
    await expect(card).toContainText(venueName)
    await expect(card).toContainText('12 Oct 2027')
    await card.getByRole('link', { name: 'View event' }).click()
  })

  await test.step('SO-C2 AC1, AC2, AC5, AC7: its details are shown with no decision to make, and Back returns to the list', async () => {
    await expect(officer).toHaveURL(new RegExp(`/safety-officer/events/${eventId}$`))
    await expect(officer.getByText('Theatre seating').first()).toBeVisible()
    await expect(officer.getByRole('button', { name: 'Reject', exact: true })).toHaveCount(0)
    await officer.getByRole('link', { name: 'Back to events' }).click()
    await expect(officer).toHaveURL(/\/safety-officer\/?$/)
  })

  await test.step('SO-C1 AC8 / SO-C2 AC8 (negative): only a Safety Officer can read them, including by API', async () => {
    for (const username of ['eo1', 'ec1', 'vs1']) {
      const refused = await request.get(`/api/safety/events/${eventId}/readiness`, {
        headers: { Authorization: `Bearer ${await apiToken(request, username)}` } })
      expect(refused.status(), `${username} reading the safety view`).toBe(403)
    }
  })
})

test('EC-C1 ECL-C4 (happy): a coordinator asks to hand an event over; the Lead approves it to another coordinator, and everyone is told', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('E2E Handover')
  const eventId = await planningEventByApi(request, eventName, '2027-10-19T09:00:00+08:00', '2027-10-19T12:00:00+08:00')

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC-C1 AC1, AC3: the coordinator gives a reason and the request is tracked as Pending', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/reassignment-request`)
    await expect(coordinator.getByRole('button', { name: 'Send request' })).toBeDisabled()
    await coordinator.getByLabel('Reason for reassignment').fill('I am on leave for the event week.')
    await coordinator.getByRole('button', { name: 'Send request' }).click()
    await expect(coordinator.getByText('Your reassignment request has been sent to the Event Coordinator Lead.')).toBeVisible()
    await coordinator.reload()
    await expect(coordinator.getByText('Pending').first()).toBeVisible()
    await expect(coordinator.getByText('The Lead is reviewing your request. You can continue planning this event while it is pending.')).toBeVisible()
  })

  await test.step('EC-C1 AC2 (conflict): a second request while one is pending is refused', async () => {
    const duplicate = await request.post(`/api/events/${eventId}/reassignment-requests`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { reason: 'Again' } })
    expect(duplicate.ok()).toBeFalsy()
  })

  const lead = await signedIn(browser, 'ecl1')
  await test.step('ECL-C4 AC2, AC4: the Lead sees the event as active, held by ec1', async () => {
    await lead.goto('/coordinator-lead/active-events')
    const card = lead.getByRole('listitem').filter({ hasText: eventName }).first()
    await expect(card).toContainText('Acme Pte Ltd')
    await expect(card).toContainText('ec1')
  })

  await test.step('EC-C1 AC4: the Lead approves it to ec2, seeing the coordinator\'s reason', async () => {
    await lead.goto('/coordinator-lead/reassignment-requests')
    const card = lead.getByRole('listitem').filter({ hasText: eventName })
    await expect(card).toContainText('I am on leave for the event week.')
    await card.getByLabel('Reassign to').selectOption({ label: 'ec2' })
    await card.getByRole('button', { name: 'Approve and reassign' }).click()
    await expect(lead.getByText(`"${eventName}" has been reassigned.`)).toBeVisible()
  })

  await test.step('ECL-C4 AC8: the active events view now shows ec2 holding it', async () => {
    await lead.goto('/coordinator-lead/active-events')
    const card = lead.getByRole('listitem').filter({ hasText: eventName }).first()
    await expect(card).toContainText('ec2')
  })

  await test.step('EC-C1 AC6 / EC-NEW4 AC3: the requester is told the outcome; the new coordinator is told it is theirs, naming who had it', async () => {
    await coordinator.reload()
    const requester = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: eventName })
    await expect(requester.filter({ hasText: 'Reassigned from ec1 to ec2.' })).toHaveCount(1)
    const next = await signedIn(browser, 'ec2')
    const handedOver = (await openBell(next)).getByRole('listitem').filter({ hasText: eventName })
      .filter({ hasText: 'You are now coordinating this event' })
    await expect(handedOver).toHaveCount(1)
    await expect(handedOver).toContainText('ec1')
  })

  await test.step('EC-C1 AC7: the event keeps its status; only the coordinator changed', async () => {
    const next = await request.get(`/api/events/${eventId}`, { headers: { Authorization: `Bearer ${await apiToken(request, 'ec2')}` } })
    expect(next.ok()).toBeTruthy()
    expect((await next.json()).status).toBe('pending')
  })
})

test('EC-C1 (negative): the Lead declines with a reason; the event stays with its coordinator, who sees why', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Handover Declined')
  const eventId = await planningEventByApi(request, eventName, '2027-10-26T09:00:00+08:00', '2027-10-26T12:00:00+08:00')
  const created = await request.post(`/api/events/${eventId}/reassignment-requests`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { reason: 'Clashes with another event.' } })
  expect(created.ok(), await created.text()).toBeTruthy()

  const lead = await signedIn(browser, 'ecl1')
  await test.step('EC-C1 AC5: Decline needs a reason, then is recorded', async () => {
    await lead.goto('/coordinator-lead/reassignment-requests')
    const card = lead.getByRole('listitem').filter({ hasText: eventName })
    const decline = card.getByRole('button', { name: 'Decline request' })
    await expect(decline).toBeDisabled()
    await card.getByLabel('Reason for declining').fill('No other coordinator is free that week.')
    await decline.click()
    await expect(lead.getByText(`The request for "${eventName}" has been declined.`)).toBeVisible()
  })

  await test.step('EC-C1 AC3, AC5, AC6: the coordinator still has the event and sees the Lead\'s reason', async () => {
    const coordinator = await signedIn(browser, 'ec1')
    await coordinator.goto(`/coordinator/events/${eventId}/reassignment-request`)
    await expect(coordinator.getByText('No other coordinator is free that week.').first()).toBeVisible()
    const event = await request.get(`/api/events/${eventId}`, { headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` } })
    expect(event.ok(), 'ec1 still coordinates the event').toBeTruthy()
  })
})
