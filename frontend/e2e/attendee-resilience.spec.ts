// @stories AT01 AT02 AT03 AT04 AT08
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, signedIn, uniqueName } from './support.ts'

test('AT01 AT02 AT03 AT04 AT08 (failure): legacy navigation and lost responses recover saved registration and withdrawal without repeat writes', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const name = uniqueName('Attendee response recovery')
  const venue = await createVenue(request, uniqueName('Attendee recovery venue'))
  const start = new Date(Date.now() + 36 * 24 * 60 * 60 * 1000)
  start.setUTCHours(1, 0, 0, 0)
  const eventId = await confirmedEventByApi(request, name, venue, start.toISOString(),
    new Date(start.getTime() + 3 * 60 * 60 * 1000).toISOString(), {
      registrationCapacity: 1, registrationOpensAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
    })
  const attendee = await signedIn(browser, 'att1')
  const token = await apiToken(request, 'att1')
  try {
    await attendee.goto('/attendee/register')
    await expect(attendee).toHaveURL(/\/attendee\/browse$/)
    await attendee.getByRole('link', { name, exact: true }).click()
    let registrations = 0, withdrawals = 0
    // Execute the real browser write on the backend, then lose only its response.
    await attendee.route(`**/api/attendee/events/${eventId}/registrations`, async route => {
      registrations++
      expect((await route.fetch()).ok()).toBeTruthy()
      await route.abort('failed')
    })
    await attendee.getByRole('button', { name: 'Register for this event' }).click()
    await expect(attendee.getByText('Registration Confirmed', { exact: true })).toBeVisible()
    await expect(attendee.getByRole('alert')).toHaveCount(0)
    await attendee.getByRole('link', { name: 'View registration confirmation' }).click()
    const records = await apiGet<{ registrationId: string; eventId: string; reference: string }[]>(request, token, '/api/attendee/registrations')
    const original = records.find(row => row.eventId === eventId)!
    expect(original).toBeDefined()
    await expect(attendee.getByText(original.reference, { exact: true })).toBeVisible()
    await attendee.route(`**/api/attendee/registrations/${original.registrationId}/withdraw`, async route => {
      withdrawals++
      expect((await route.fetch()).ok()).toBeTruthy()
      await route.abort('failed')
    })
    await attendee.getByRole('button', { name: 'Withdraw Registration' }).click()
    await attendee.getByRole('dialog').getByRole('button', { name: 'Confirm withdrawal' }).click()
    await expect(attendee.getByText('Withdrawn', { exact: true })).toBeVisible()
    await expect(attendee.getByText('Withdrawal date', { exact: true })).toBeVisible()
    await expect(attendee.getByRole('alert')).toHaveCount(0)
    await expect(attendee.getByRole('button', { name: 'Withdraw Registration' })).toHaveCount(0)
    expect(registrations).toBe(1)
    expect(withdrawals).toBe(1)
    const notices = await apiGet<{ type: string; eventId: string; linkPath: string }[]>(request, token, '/api/notifications')
    const receipts = notices.filter(row => row.eventId === eventId)
    expect(receipts.filter(row => row.type === 'attendee_registration_confirmed')).toHaveLength(1)
    expect(receipts.filter(row => row.type === 'attendee_registration_withdrawn')).toHaveLength(1)
    expect(receipts.every(row => row.linkPath === `/attendee/my-registrations/${original.registrationId}`)).toBe(true)
    const current = await apiGet<{ availablePlaces: number; registrationStatus: string }>(request, token, `/api/attendee/events/${eventId}`)
    expect(current).toMatchObject({ availablePlaces: 1, registrationStatus: 'withdrawn' })
    await attendee.reload()
    await expect(attendee.getByText('Withdrawn', { exact: true })).toBeVisible()
    await expect(attendee.getByText(original.reference, { exact: true })).toBeVisible()
  } finally { await attendee.context().close() }
})
