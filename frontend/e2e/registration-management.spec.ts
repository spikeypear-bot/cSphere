// @stories EO10 EO11 EO12 AT07 AT01 AT08
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, signedIn, uniqueName } from './support.ts'

// The organiser's registration controls and what attendees see because of
// them. Expected wording comes from the EO10, EO11, EO12 and AT07 ACs
// (Sprint Backlog 4). Times typed into the period editor are Singapore time.

const DAY = 24 * 60 * 60 * 1000

/** A moment as the YYYY-MM-DDTHH:mm a datetime-local input shows in Singapore time. */
function sgtInput(at: Date): string {
  return new Date(at.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)
}

async function eventWithRegistration(request: APIRequestContext, prefix: string, capacity: number, opensAt: Date, startsIn = 30 * DAY) {
  const start = new Date(Date.now() + startsIn)
  start.setUTCHours(1, 0, 0, 0)
  const eventName = uniqueName(prefix)
  const eventId = await confirmedEventByApi(request, eventName, await createVenue(request, uniqueName(`${prefix} venue`)),
    start.toISOString(), new Date(start.getTime() + 3 * 60 * 60 * 1000).toISOString(),
    { registrationCapacity: capacity, registrationOpensAt: opensAt.toISOString() })
  return { eventId, eventName, start }
}

test('EO10 EO11 AT07 (boundary): the organiser sees registrations and sets capacity; at capacity attendees are told it is full, and a withdrawal frees the place', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const { eventId } = await eventWithRegistration(request, 'E2E Capacity', 2, new Date(Date.now() - DAY))

  const organiser = await signedIn(browser, 'eo1')
  const info = organiser.getByRole('region', { name: /Registration information/ })
  await test.step('EO10 AC2, AC7: the organiser sees capacity, places remaining, and that nobody has registered yet', async () => {
    await organiser.goto(`/organiser/events/${eventId}`)
    await expect(info).toContainText('No registrations yet.')
    await expect(info.getByText('Places remaining').locator('..')).toContainText('2')
  })

  await test.step('EO11 AC2 (negative): a capacity of 0 is refused and the capacity is unchanged', async () => {
    await organiser.getByLabel('Registration capacity').fill('0')
    await organiser.getByRole('button', { name: 'Save capacity' }).click()
    await expect(organiser.getByText('Capacity must be a positive whole number.')).toBeVisible()
    await expect(info.getByText('Capacity', { exact: true }).locator('..')).toContainText('2')
  })

  await test.step('EO11 AC1, AC7 (boundary): a capacity of 1 is saved and shown', async () => {
    await organiser.getByLabel('Registration capacity').fill('1')
    await organiser.getByRole('button', { name: 'Save capacity' }).click()
    await expect(organiser.getByText('Capacity saved: 1 places.')).toBeVisible()
  })

  const first = await signedIn(browser, 'att1')
  await test.step('AT01: one attendee registers and takes the only place', async () => {
    await first.goto(`/attendee/events/${eventId}`)
    await first.getByRole('button', { name: 'Register for this event' }).click()
    await expect(first.getByText(/Registration Confirmed/)).toBeVisible()
  })

  const second = await signedIn(browser, 'att2')
  await test.step('AT07 AC1, AC2, AC3, AC4: the next attendee is told it is full and cannot register', async () => {
    await second.goto(`/attendee/events/${eventId}`)
    await expect(second.getByText('Registration is full. This event is full. No places are available.')).toBeVisible()
    await expect(second.getByRole('button', { name: 'Register for this event' })).toHaveCount(0)
  })

  await test.step('AT07 AC7 / EO11 AC5 (conflict): a registration sent anyway is refused and creates nothing', async () => {
    const refused = await request.post(`/api/attendee/events/${eventId}/registrations`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'att2')}` }, data: {} })
    expect(refused.ok()).toBeFalsy()
    const mine = await apiGet<{ eventId: string }[]>(request, await apiToken(request, 'att2'), '/api/attendee/registrations')
    expect(mine.filter((r) => r.eventId === eventId)).toHaveLength(0)
  })

  await test.step('EO10 AC3, AC5: the organiser sees the attendee, their reference and Active status, and no places left', async () => {
    const own = (await apiGet<{ eventId: string; reference: string }[]>(request, await apiToken(request, 'att1'), '/api/attendee/registrations'))
      .find((r) => r.eventId === eventId)!
    await organiser.reload()
    const row = info.getByRole('row').filter({ hasText: own.reference })
    await expect(row).toContainText('Active (confirmed)')
    await expect(info.getByText('Places remaining').locator('..')).toContainText('0')
  })

  await test.step('EO10 AC1 (negative): another organisation\'s organiser and an attendee cannot read the list, including by API', async () => {
    for (const username of ['eo3', 'att1']) {
      const refused = await request.get(`/api/events/${eventId}/registrations`, {
        headers: { Authorization: `Bearer ${await apiToken(request, username)}` } })
      expect([403, 404], `${username} reading registrations`).toContain(refused.status())
    }
  })

  await test.step('AT07 AC8 / EO10 AC4, AC5: after a withdrawal the place is free again, and the organiser sees Withdrawn', async () => {
    const own = (await apiGet<{ eventId: string; registrationId: string; reference: string }[]>(request, await apiToken(request, 'att1'), '/api/attendee/registrations'))
      .find((r) => r.eventId === eventId)!
    const withdrawn = await request.post(`/api/attendee/registrations/${own.registrationId}/withdraw`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'att1')}` }, data: {} })
    expect(withdrawn.ok(), await withdrawn.text()).toBeTruthy()
    await second.reload()
    await expect(second.getByRole('button', { name: 'Register for this event' })).toBeVisible()
    await organiser.reload()
    await expect(info.getByRole('row').filter({ hasText: own.reference })).toContainText('Withdrawn')
    await expect(info.getByText('Places remaining').locator('..')).toContainText('1')
  })
})

test('EO12 (boundary): the organiser sets the registration period in Singapore time; bad periods are refused, and attendees see when it opens', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const opens = new Date(Date.now() + 2 * DAY)
  opens.setUTCSeconds(0, 0)
  const { eventId, start } = await eventWithRegistration(request, 'E2E Period', 10, opens)

  const attendee = await signedIn(browser, 'att1')
  await test.step('EO12 AC8: before it opens, attendees are told it has not opened yet and cannot register', async () => {
    await attendee.goto(`/attendee/events/${eventId}`)
    await expect(attendee.getByText(/Registration has not opened yet/)).toBeVisible()
    await expect(attendee.getByRole('button', { name: 'Register for this event' })).toHaveCount(0)
  })

  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${eventId}`)
  const opensField = organiser.getByLabel('Registration opens (SGT)')
  const closesField = organiser.getByLabel('Registration closes (SGT)')
  const save = organiser.getByRole('button', { name: 'Save registration period' })

  await test.step('EO12 AC3 (boundary): a closing time equal to the opening time is refused', async () => {
    await opensField.fill(sgtInput(opens))
    await closesField.fill(sgtInput(opens))
    await save.click()
    await expect(organiser.getByText('The registration closing date and time must be after the opening date and time.')).toBeVisible()
  })

  await test.step('EO12 AC5 (boundary): closing after the event starts is refused', async () => {
    await closesField.fill(sgtInput(new Date(start.getTime() + 60 * 1000)))
    await save.click()
    await expect(organiser.getByText('Registration must close no later than the event start date and time.')).toBeVisible()
  })

  await test.step('EO12 AC2, AC14 (boundary): closing exactly at the event start is saved, and the status is shown', async () => {
    await closesField.fill(sgtInput(start))
    await save.click()
    await expect(organiser.getByText('Registration period saved. Status: Not yet open.')).toBeVisible()
  })

  await test.step('EO12 AC1 (negative): another organisation\'s organiser cannot change it by API', async () => {
    const refused = await request.put(`/api/events/${eventId}/registration-period`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'eo3')}` },
      data: { opensAt: opens.toISOString(), closesAt: start.toISOString() } })
    expect([403, 404]).toContain(refused.status())
  })
})
