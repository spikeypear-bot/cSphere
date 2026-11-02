// @stories EO03 EC-NEW3 EC07 TS01 SO-C1 EC08
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, createVenue, planningEventByApi, signedIn, uniqueDay, uniqueName, openBell } from './support.ts'

test('EO03 EC-NEW3 EC07 TS01 (happy): EC discovers an EO equipment proposal and TS discovers the revised actual quantities', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const name = uniqueName('Release 1 equipment'), day = uniqueDay()
  const eventId = await planningEventByApi(request, name, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const token = await apiToken(request, 'ec1')
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, token, '/api/equipment/catalogue')
  const screen = catalogue.find(item => item.equipmentName === 'Projection Screen')!
  const old = await request.post(`/api/events/${eventId}/equipment-request`, {
    headers: { Authorization: `Bearer ${token}` }, data: { technicalRequirement: 'Projection', items: [{ equipmentId: screen.equipmentId, quantity: 2 }] },
  })
  expect(old.ok()).toBeTruthy()
  const oldId = (await old.json()).requestId as string
  const organiser = await signedIn(browser, 'eo1'), coordinator = await signedIn(browser, 'ec1'), technician = await signedIn(browser, 'ts1')
  await coordinator.goto(`/coordinator/events/${eventId}`)
  await technician.goto('/technical-support/requests')
  await expect(technician.getByRole('listitem').filter({ hasText: name })).toContainText('2 × Projection Screen')
  await organiser.goto(`/organiser/events/${eventId}/change`)
  await organiser.getByLabel('Projection Screen', { exact: true }).check()
  await organiser.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true }).fill('3')
  await organiser.getByRole('button', { name: 'Review the change' }).click()
  await expect(organiser.getByRole('table')).toContainText('3 × Projection Screen')
  await organiser.getByLabel('Why is this change needed?').fill('The presentation needs three screens.')
  await organiser.getByRole('button', { name: 'Send change request' }).click()
  const pending = coordinator.getByRole('region', { name: 'Change requested' })
  await expect(pending).toBeVisible({ timeout: 15_000 })
  await pending.getByRole('link', { name: 'Review and decide' }).click()
  await expect(coordinator.getByRole('table')).toContainText('3 × Projection Screen')
  for (const checkbox of await coordinator.getByRole('checkbox', { name: 'I have considered this' }).all()) await checkbox.check()
  await coordinator.getByRole('button', { name: 'Approve and apply the change' }).click()
  await expect(coordinator.getByText('Change approved and applied. The organiser has been told.')).toBeVisible()
  await coordinator.goto(`/coordinator/events/${eventId}/equipment-request`)
  await coordinator.getByRole('button', { name: 'Revise request' }).click()
  await expect(coordinator.getByRole('spinbutton', { name: 'Projection Screen quantity', exact: true })).toHaveValue('3')
  await coordinator.getByRole('button', { name: 'Submit revision' }).click()
  await expect(coordinator.getByText(/Equipment request revised/)).toBeVisible()
  const revised = technician.getByRole('listitem').filter({ hasText: name })
  await expect(revised).toContainText('3 × Projection Screen', { timeout: 15_000 })
  await expect(revised.getByRole('link')).not.toHaveAttribute('href', `/technical-support/requests/${oldId}`)
  await organiser.context().close(); await coordinator.context().close(); await technician.context().close()
})

test('EC08 SO-C1 (happy): an open SO queue and notification bell discover EC safety submission', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const name = uniqueName('Release 1 safety'), day = uniqueDay()
  const venueId = await createVenue(request, uniqueName('Release 1 safety venue'))
  const eventId = await planningEventByApi(request, name, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const ec = { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }
  const booking = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: ec, data: { venueId } })
  expect(booking.ok()).toBeTruthy()
  const approved = await request.patch(`/api/venue-bookings/${(await booking.json()).bookingId}/approve`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'vs1')}` },
  })
  expect(approved.ok()).toBeTruthy()
  const officer = await signedIn(browser, 'so1'), coordinator = await signedIn(browser, 'ec1')
  await officer.goto('/safety-officer/reviews')
  const waiting = officer.locator('section').filter({ has: officer.getByRole('heading', { name: 'Waiting for your safety review' }) })
  await expect(waiting.getByRole('link', { name })).toHaveCount(0)
  await coordinator.goto(`/coordinator/events/${eventId}`)
  await coordinator.getByRole('button', { name: /Send for safety review/i }).click()
  await expect(waiting.getByRole('link', { name })).toBeVisible({ timeout: 15_000 })
  const bell = await openBell(officer)
  await expect(bell.getByText(`"${name}" is waiting for your safety review`)).toBeVisible({ timeout: 35_000 })
  await bell.getByText(`"${name}" is waiting for your safety review`).click()
  await expect(officer).toHaveURL(new RegExp(`/safety-officer/events/${eventId}$`))
  await officer.context().close(); await coordinator.context().close()
})
