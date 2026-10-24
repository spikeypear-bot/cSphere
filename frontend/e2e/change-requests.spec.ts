// @stories EO03 EC-NEW3 EC-NEW5
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, createVenue, leadAssigns, requestIdOf, signedIn, submitRequest, uniqueName } from './support.ts'

// EO03 + EC-NEW3 through the browser and the packaged backend: an organiser
// asks to change a booked event; the coordinator sees what it would affect,
// must acknowledge it, and approves; the organiser sees the new details.
// Expected values come from the ACs, not from what the page happened to show.

test('an organiser asks for a change and the coordinator approves it after acknowledging its impact (EO03, EC-NEW3)', async ({ browser, request }) => {
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
