// @stories AT04 AT01 AT02
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, signedIn, uniqueName } from './support.ts'

test('AT04 AT01 AT02 (happy): register, view own status, open latest event details and refuse another attendee', async ({ browser, request }, testInfo) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('AT04 registration status')
  const venueId = await createVenue(request, uniqueName('AT04 venue'))
  const start = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
  start.setUTCHours(1, 0, 0, 0)
  const eventId = await confirmedEventByApi(request, eventName, venueId,
    start.toISOString(), new Date(start.getTime() + 3 * 60 * 60 * 1000).toISOString(), {
      registrationCapacity: 10, registrationOpensAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
    })
  const attendee = await signedIn(browser, 'att1')
  try {
    await attendee.goto(`/attendee/events/${eventId}`)
    await expect(attendee.getByRole('heading', { name: eventName })).toBeVisible()
    await attendee.getByRole('button', { name: 'Register for this event' }).click()
    await expect(attendee.getByText(/You’re registered/)).toBeVisible()
    const token = await apiToken(request, 'att1')
    const registrations = await apiGet<{ registrationId: string; reference: string; eventId: string }[]>(request, token, '/api/attendee/registrations')
    const own = registrations.find(registration => registration.eventId === eventId)!
    expect(own).toBeDefined()
    await attendee.getByRole('link', { name: 'My Registrations', exact: true }).last().click()
    const card = attendee.getByRole('listitem').filter({ hasText: eventName })
    await expect(card.getByText(own.reference, { exact: true })).toBeVisible()
    await expect(card.getByText('Registration status', { exact: true })).toBeVisible()
    await expect(card.getByText('Event status', { exact: true })).toBeVisible()
    await card.getByRole('link', { name: 'View registration' }).click()
    await expect(attendee.getByText('Withdrawal availability', { exact: true })).toBeVisible()
    await expect(attendee.getByText('Available. Withdrawal is available before the event starts.', { exact: true })).toBeVisible()
    await attendee.screenshot({ path: testInfo.outputPath('registration-details-desktop.png'), fullPage: true })
    await attendee.setViewportSize({ width: 390, height: 844 })
    await expect(attendee.getByText(own.reference, { exact: true })).toBeVisible()
    await attendee.screenshot({ path: testInfo.outputPath('registration-details-mobile.png'), fullPage: true })
    await attendee.getByRole('link', { name: 'View latest event details' }).click()
    await expect(attendee.getByRole('heading', { name: eventName })).toBeVisible()
    await expect(attendee.getByText(/You’re registered/)).toBeVisible()
    const otherToken = await apiToken(request, 'att2')
    const denied = await request.get(`/api/attendee/registrations/${own.registrationId}`, {
      headers: { Authorization: `Bearer ${otherToken}` },
    })
    expect(denied.status()).toBe(404)
  } finally {
    await attendee.context().close()
  }
})
