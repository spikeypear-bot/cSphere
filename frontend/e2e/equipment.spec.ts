// @stories EC07 TS01 TS02 TS06 TS03 TS09 EC-NEW4
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, openBell, planningEventByApi, signedIn, uniqueDay, uniqueName } from './support.ts'

// Equipment from request to decision, through the coordinator's and Technical
// Support's screens, using the seeded catalogue (Projection Screen: 6 owned,
// tracked by quantity). Each run uses its own date so reruns never compete
// for the screens an earlier run reserved. Expected wording comes from the
// EC07, TS01, TS02, TS06 and TS03 ACs (Sprint Backlogs 1–3).

async function equipmentRequestOf(request: APIRequestContext, eventId: string) {
  const all = await apiGet<{ requestId: string; status: string }[]>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}/equipment-requests`)
  expect(all, 'the event has exactly one equipment request').toHaveLength(1)
  return all[0]
}

async function eventStatus(request: APIRequestContext, eventId: string) {
  return (await apiGet<{ status: string }>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}`)).status
}

test('EC07 TS01 TS02 TS06 (happy): the coordinator requests screens; Technical Support reserves them and approves; the coordinator sees it approved', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const day = uniqueDay()
  const eventName = uniqueName('E2E Screens')
  const eventId = await planningEventByApi(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC07 AC2, AC5 (boundary): a quantity of 0 cannot be submitted', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/equipment-request`)
    await coordinator.getByRole('button', { name: 'Add item' }).click()
    await coordinator.getByRole('combobox', { name: /^Equipment/ }).selectOption({ label: 'Projection Screen (6 in catalogue)' })
    await coordinator.getByLabel('Quantity').fill('0')
    await expect(coordinator.getByRole('button', { name: 'Submit equipment request' })).toBeDisabled()
  })

  await test.step('EC07 AC3, AC7, AC8: two screens with a note are submitted for Technical Support to review', async () => {
    await coordinator.getByLabel('Quantity').fill('2')
    await coordinator.getByLabel('Technical requirements or notes').fill('Two screens either side of the stage.')
    await coordinator.getByRole('button', { name: 'Submit equipment request' }).click()
    await expect(coordinator.getByText('Equipment request submitted to Technical Support for review.')).toBeVisible()
    expect((await equipmentRequestOf(request, eventId)).status).toBe('processing')
  })

  await test.step('EC07 AC9, AC10, AC12: after reloading it is shown as awaiting review, a second one is not offered, and the event is unchanged', async () => {
    await coordinator.reload()
    await expect(coordinator.getByText('Awaiting Technical Support review').first()).toBeVisible()
    await expect(coordinator.getByText('Projection Screen').first()).toBeVisible()
    await expect(coordinator.getByRole('button', { name: 'Submit equipment request' })).toHaveCount(0)
    expect(await eventStatus(request, eventId)).toBe('pending')
  })

  const { requestId } = await equipmentRequestOf(request, eventId)
  const technician = await signedIn(browser, 'ts1')
  await test.step('TS01 AC1, AC2: Technical Support finds the request and sees what was asked for, and why', async () => {
    await technician.goto('/technical-support/requests')
    await technician.getByRole('link').filter({ hasText: eventName }).first().click()
    await expect(technician).toHaveURL(new RegExp(`/technical-support/requests/${requestId}$`))
    await expect(technician.getByText('Two screens either side of the stage.').first()).toBeVisible()
    await expect(technician.getByText('Projection Screen').first()).toBeVisible()
  })

  await test.step('TS06 AC4, AC5 (negative): it cannot be approved before the screens are reserved', async () => {
    await expect(technician.getByRole('button', { name: 'Approve request' })).toBeDisabled()
  })

  await test.step('TS02 AC5, AC10, AC11: reserving the remaining two confirms what was reserved', async () => {
    await technician.getByRole('button', { name: 'Reserve all remaining (2 units)' }).click()
    await expect(technician.getByText('Reserved 2 units for this event.')).toBeVisible()
  })

  await test.step('TS06 AC3, AC7, AC11: once covered it is approved, and the event itself is not confirmed', async () => {
    await technician.getByRole('button', { name: 'Approve request' }).click()
    await expect.poll(async () => (await equipmentRequestOf(request, eventId)).status).toBe('approved')
    expect(await eventStatus(request, eventId)).toBe('pending')
  })

  await test.step('TS06 AC8 / EC-NEW4 AC11: the coordinator is told and sees it approved', async () => {
    await coordinator.reload()
    await expect((await openBell(coordinator)).getByText(`Equipment request for "${eventName}" was approved`)).toBeVisible()
    await coordinator.keyboard.press('Escape')
    await expect(coordinator.getByText('Approved').first()).toBeVisible()
  })

  await test.step('TS02 AC14 / TS06 AC12 (negative): only Technical Support can decide, including by API', async () => {
    const refused = await request.patch(`/api/equipment-requests/${requestId}/status`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { status: 'rejected', rejectReason: 'x' } })
    expect(refused.status()).toBe(403)
  })
})

test('TS01 TS06 (negative): asking for more screens than exist is flagged as short; Technical Support rejects it with a reason the coordinator sees', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const day = uniqueDay()
  const eventName = uniqueName('E2E Too Many Screens')
  const eventId = await planningEventByApi(request, eventName, `${day}T14:00:00+08:00`, `${day}T17:00:00+08:00`)
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, await apiToken(request, 'ec1'), '/api/equipment/catalogue')
  const screens = catalogue.find((e) => e.equipmentName === 'Projection Screen')!
  const created = await request.post(`/api/events/${eventId}/equipment-request`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` },
    data: { technicalRequirement: 'Screens for every breakout room.', items: [{ equipmentId: screens.equipmentId, quantity: 7 }] } })
  expect(created.ok(), await created.text()).toBeTruthy()
  const { requestId } = await created.json()

  const technician = await signedIn(browser, 'ts1')
  await test.step('TS01 AC7, AC10: the shortfall is flagged and the request cannot be approved as it is', async () => {
    await technician.goto(`/technical-support/requests/${requestId}`)
    await expect(technician.getByText('Short: only 6 of the 7 still needed are free for this window.')).toBeVisible()
    await expect(technician.getByRole('button', { name: 'Approve request' })).toBeDisabled()
  })

  await test.step('TS06 AC6, AC10: rejecting needs a reason and reserves nothing', async () => {
    await expect(technician.getByText('Approve is available once every requested item is reserved for the whole event.')).toBeVisible()
    await technician.getByRole('button', { name: 'Reject…' }).click()
    const confirm = technician.getByRole('button', { name: 'Reject request' })
    await expect(confirm).toBeDisabled()
    await technician.getByLabel('Reason for rejection').fill('We own only six screens.')
    await confirm.click()
    await expect(technician.getByText('This request was rejected.')).toBeVisible()
    await expect(technician.getByRole('region', { name: 'Reserved for this event' })).not.toContainText('Projection Screen')
  })

  await test.step('TS06 AC8 / EC-NEW4 AC11: the coordinator is told why', async () => {
    const coordinator = await signedIn(browser, 'ec1')
    const notice = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: `Equipment request for "${eventName}" was rejected` })
    await expect(notice).toContainText('We own only six screens.')
  })
})

test('TS03 (happy): Technical Support marks a projector Faulty for a period, and it is listed as blocked for those dates', async ({ browser }) => {
  test.setTimeout(120_000)
  const day = uniqueDay()
  const technician = await signedIn(browser, 'ts1')
  await test.step('TS03 AC2, AC3: a unit is marked Faulty for a period and the saved change is confirmed', async () => {
    await technician.goto('/technical-support/faulty/new')
    const equipment = technician.getByRole('combobox', { name: /^Equipment/ })
    const projector = await equipment.locator('option').filter({ hasText: /^Projector/ }).getAttribute('value')
    await equipment.selectOption(projector!)
    const unit = technician.getByRole('combobox', { name: /^Unit/ })
    await expect(unit.locator('option').nth(1)).toBeAttached()
    const serial = (await unit.locator('option').nth(1).getAttribute('value'))!
    await unit.selectOption(serial)
    await technician.getByRole('combobox', { name: /^Status/ }).selectOption('Faulty')
    await technician.getByLabel('Description').fill('Lamp failure, flickers after five minutes.')
    await technician.getByLabel('Starts').fill(`${day}T08:00`)
    await technician.getByLabel('Ends (optional)').fill(`${day}T18:00`)
    await technician.getByRole('button', { name: 'Report problem' }).click()
    await expect(technician.getByText(`Projector ${serial} marked Faulty for the chosen dates.`)).toBeVisible()
    await expect(technician.getByLabel('Blocked units')).toContainText(serial)
  })
})

test('TS09 (conflict): two of six screens are reported damaged while an event holds all six; Technical Support marks it short and the coordinator, not the organiser, is told', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const day = uniqueDay()
  const eventName = uniqueName('E2E Damaged Screens')
  const eventId = await planningEventByApi(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const ec = { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }
  const ts = { Authorization: `Bearer ${await apiToken(request, 'ts1')}` }
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, await apiToken(request, 'ec1'), '/api/equipment/catalogue')
  const screens = catalogue.find((e) => e.equipmentName === 'Projection Screen')!
  const created = await request.post(`/api/events/${eventId}/equipment-request`, { headers: ec,
    data: { technicalRequirement: 'Six screens around the hall.', items: [{ equipmentId: screens.equipmentId, quantity: 6 }] } })
  expect(created.ok(), await created.text()).toBeTruthy()
  const reserved = await request.post('/api/equipment/reservations', { headers: ts, data: { eventId, equipmentId: screens.equipmentId,
    quantity: 6, start: `${day}T01:00:00Z`, end: `${day}T04:00:00Z` } })
  expect(reserved.ok(), await reserved.text()).toBeTruthy()
  const approved = await request.patch(`/api/equipment-requests/${(await created.json()).requestId}/status`, { headers: ts, data: { status: 'approved' } })
  expect(approved.ok(), await approved.text()).toBeTruthy()

  const technician = await signedIn(browser, 'ts1')
  await test.step('TS09 AC1, AC3, AC6: reporting 2 damaged screens over the event lists it as affected, awaiting a decision', async () => {
    await technician.goto('/technical-support/faulty/new')
    const equipment = technician.getByRole('combobox', { name: /^Equipment/ })
    await equipment.selectOption((await equipment.locator('option').filter({ hasText: /^Projection Screen/ }).getAttribute('value'))!)
    await technician.getByLabel('Units affected').fill('2')
    await technician.getByLabel('Description').fill('Two screens torn in transit.')
    await technician.getByLabel('Starts').fill(`${day}T08:00`)
    await technician.getByLabel('Ends (optional)').fill(`${day}T18:00`)
    await technician.getByRole('button', { name: 'Report problem' }).click()
    const affected = technician.getByRole('list', { name: 'Affected events' }).getByRole('listitem').filter({ hasText: eventName })
    await expect(affected).toContainText('Awaiting a decision')
    await expect(technician.getByRole('button', { name: 'Close report' })).toBeDisabled()
  })

  await test.step('TS09 AC13, AC14, AC17: taking 2 off the event leaves it Short by 2, and the report can then close', async () => {
    const affected = technician.getByRole('list', { name: 'Affected events' }).getByRole('listitem').filter({ hasText: eventName })
    await affected.getByLabel(`Units to take off ${eventName}`).fill('2')
    await affected.getByRole('button', { name: 'Apply' }).click()
    await expect(technician.getByText(`${eventName}: reservation updated.`)).toBeVisible()
    await expect(affected).toContainText('Short by 2')
    await technician.getByRole('button', { name: 'Close report' }).click()
    await expect(technician.getByText('Report closed.')).toBeVisible()
  })

  await test.step('TS09 AC18, AC19: the coordinator is told the outcome; the organiser is not', async () => {
    const coordinator = await signedIn(browser, 'ec1')
    await expect((await openBell(coordinator)).getByText(`Damaged equipment affects "${eventName}"`)).toBeVisible()
    const organiser = await signedIn(browser, 'eo1')
    await expect((await openBell(organiser)).getByText(`Damaged equipment affects "${eventName}"`)).toHaveCount(0)
  })
})
