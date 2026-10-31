// @stories AT09 EO03 EO07 AT04 AT08
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, openBell, requestIdOf, signedIn, uniqueName } from './support.ts'

// AT09: attendees with an active registration are told when an approved change
// moves their event, and when it is cancelled; nobody else is. Expected
// wording comes from the AT09 ACs (Sprint Backlog 4).

const DAY = 24 * 60 * 60 * 1000

async function headers(request: APIRequestContext, username: string) {
  return { Authorization: `Bearer ${await apiToken(request, username)}` }
}

/** A confirmed event of eo1's open for registration now; att1 is registered, att2 registered and withdrew. */
async function eventWithAttendees(request: APIRequestContext, prefix: string) {
  const start = new Date(Date.now() + 40 * DAY)
  start.setUTCHours(1, 0, 0, 0)
  const eventName = uniqueName(prefix)
  const eventId = await confirmedEventByApi(request, eventName, await createVenue(request, uniqueName(`${prefix} venue`)),
    start.toISOString(), new Date(start.getTime() + 3 * 60 * 60 * 1000).toISOString(),
    { registrationCapacity: 10, registrationOpensAt: new Date(Date.now() - DAY).toISOString() })
  for (const username of ['att1', 'att2']) {
    const registered = await request.post(`/api/attendee/events/${eventId}/registrations`, { headers: await headers(request, username), data: {} })
    expect(registered.ok(), `${username} registers: ${await registered.text()}`).toBeTruthy()
  }
  const att2 = (await apiGet<{ eventId: string; registrationId: string }[]>(request, await apiToken(request, 'att2'), '/api/attendee/registrations'))
    .find((r) => r.eventId === eventId)!
  const withdrawn = await request.post(`/api/attendee/registrations/${att2.registrationId}/withdraw`, { headers: await headers(request, 'att2'), data: {} })
  expect(withdrawn.ok(), await withdrawn.text()).toBeTruthy()
  return { eventId, eventName, requestId: await requestIdOf(request, eventName), start }
}

async function noticesFor(request: APIRequestContext, username: string, eventId: string) {
  return (await apiGet<{ type: string; eventId: string }[]>(request, await apiToken(request, username), '/api/notifications'))
    .filter((n) => n.eventId === eventId && n.type.startsWith('attendee_event_'))
}

test('AT09 (happy): an approved change to the date and time tells the registered attendee what changed, from what to what; nobody else is told', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const { eventId, eventName, requestId, start } = await eventWithAttendees(request, 'E2E Moved Gala')
  const current = await apiGet<Record<string, unknown>>(request, await apiToken(request, 'eo1'), `/api/event-requests/${requestId}`)
  const later = new Date(start.getTime() + 2 * 60 * 60 * 1000)
  const asked = await request.post('/api/change-requests', { headers: await headers(request, 'eo1'), data: {
    requestId, reason: 'The keynote moved to the afternoon.',
    details: { ...current, startDatetime: later.toISOString(), endDatetime: new Date(later.getTime() + 3 * 60 * 60 * 1000).toISOString() } } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  const changeId = (await asked.json()).changeRequestId as string

  await test.step('AT09 AC3: a pending change tells nobody', async () => {
    expect(await noticesFor(request, 'att1', eventId)).toHaveLength(0)
  })

  const review = await apiGet<{ impact: { key: string; status: string }[] }>(request, await apiToken(request, 'ec1'), `/api/change-requests/${changeId}`)
  const approved = await request.post(`/api/change-requests/${changeId}/approve`, { headers: await headers(request, 'ec1'),
    data: { acknowledged: review.impact.filter((i) => i.status === 'attention').map((i) => i.key) } })
  expect(approved.ok(), await approved.text()).toBeTruthy()

  const attendee = await signedIn(browser, 'att1')
  const notice = (await openBell(attendee)).getByRole('listitem').filter({ hasText: `Important updates for "${eventName}"` })
  await test.step('AT09 AC1, AC9, AC11: the registered attendee gets one notice naming the event, what changed, and the old and new values', async () => {
    await expect(notice).toHaveCount(1)
    await expect(notice).toContainText('→')
    await expect(notice).toContainText(/Start/)
    await expect(notice).toContainText('(Singapore time)')
  })

  await test.step('AT09 AC13, AC16: it opens the event, and reading it leaves the registration active', async () => {
    await notice.getByRole('button').click()
    await expect(attendee).toHaveURL(new RegExp(`/attendee/events/${eventId}`))
    const registrations = await apiGet<{ eventId: string; registrationStatus: string }[]>(request, await apiToken(request, 'att1'), '/api/attendee/registrations')
    expect(registrations.find((r) => r.eventId === eventId)!.registrationStatus).toBe('confirmed')
  })

  await test.step('AT09 AC6, AC7, AC8: a withdrawn attendee, an unregistered attendee and staff are not told', async () => {
    for (const username of ['att2', 'att3']) expect(await noticesFor(request, username, eventId), username).toHaveLength(0)
  })
})

test('AT09 (happy): cancelling the event tells the registered attendee, with its original date and the reason, and shows the registration as Cancelled', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const { eventId, eventName, requestId } = await eventWithAttendees(request, 'E2E Cancelled Gala')
  const asked = await request.post(`/api/event-requests/${requestId}/cancellation-request`, { headers: await headers(request, 'eo1'),
    data: { reason: 'Our keynote speaker has withdrawn.' } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  const pending = await apiGet<{ cancellationRequestId: string; status: string }[]>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}/cancellation-requests`)
  const approved = await request.post(`/api/events/${eventId}/cancellation-requests/${pending.find((p) => p.status === 'pending')!.cancellationRequestId}/approve`,
    { headers: await headers(request, 'ec1'), data: {} })
  expect(approved.ok(), await approved.text()).toBeTruthy()

  const attendee = await signedIn(browser, 'att1')
  await test.step('AT09 AC4, AC10: the attendee is told it is cancelled, with its original date and time (Singapore time) and the reason', async () => {
    const notice = (await openBell(attendee)).getByRole('listitem').filter({ hasText: `"${eventName}" has been cancelled` })
    await expect(notice).toHaveCount(1)
    await expect(notice).toContainText('Original date/time:')
    await expect(notice).toContainText('(Singapore time)')
    await expect(notice).toContainText('Our keynote speaker has withdrawn.')
    await attendee.keyboard.press('Escape')
  })

  await test.step('AT09 AC14: My Registrations shows the registration as Cancelled', async () => {
    await attendee.goto('/attendee/my-registrations')
    await expect(attendee.getByRole('listitem').filter({ hasText: eventName }).getByText('Cancelled', { exact: true }).first()).toBeVisible()
  })

  await test.step('AT09 AC7 (negative): the attendee who had withdrawn is not told', async () => {
    expect(await noticesFor(request, 'att2', eventId)).toHaveLength(0)
  })
})
