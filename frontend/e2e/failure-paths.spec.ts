// @stories EO01 EO02 ECL-C3 ELC-C6 EC02 EC03 VS03 EC08 SO-C4 SO-C6 EC-NEW2 EO15
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, createVenue, leadAssigns, requestIdOf, signedIn, submitRequest, uniqueName } from './support.ts'

// IS212 Week 9 / rubric "failure scenarios": the two ways the core journey
// most often turns back, checked through the browser.

test('a rejected request tells the organiser it was rejected and why (EC02, EC-NEW2 AC3)', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Rejected Gala')
  const organiser = await signedIn(browser, 'eo1')
  await submitRequest(organiser, eventName, '2027-07-01T09:00', '2027-07-01T12:00')
  const requestId = await requestIdOf(request, eventName)
  await leadAssigns(await signedIn(browser, 'ecl1'), requestId)

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC02: the coordinator rejects with a reason', async () => {
    await coordinator.goto(`/coordinator/requests/${requestId}`)
    await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Reject' }).click()
    await coordinator.getByLabel(/reason/i).fill('The date clashes with the annual audit.')
    await coordinator.getByRole('button', { name: 'Confirm rejection' }).click()
    await expect(coordinator.getByText('Rejected', { exact: true }).first()).toBeVisible()
  })

  await test.step('EC-NEW2 AC3, AC29 / EO15: the organiser sees Rejected and the reason, with no further actions', async () => {
    await organiser.goto('/organiser')
    const row = organiser.getByRole('listitem').filter({ hasText: eventName })
    await expect(row.getByText('Rejected', { exact: true }).first()).toBeVisible()
    await organiser.getByRole('button', { name: /Notifications/ }).click()
    await expect(organiser.getByText('The date clashes with the annual audit.').first()).toBeVisible()
    await expect(row.getByRole('link', { name: /Continue editing|Respond|View event/ })).toHaveCount(0)
  })
})

test('SO-C6, EC08 AC23, AC24: the Safety Officer asks for changes to named checklist items and the coordinator can fix and resend', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('E2E Changes Fair')
  const venueName = uniqueName('E2E Harbour Room')
  await createVenue(request, venueName)
  const organiser = await signedIn(browser, 'eo1')
  await submitRequest(organiser, eventName, '2027-08-03T09:00', '2027-08-03T12:00')
  const requestId = await requestIdOf(request, eventName)
  await leadAssigns(await signedIn(browser, 'ecl1'), requestId)

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/requests/${requestId}`)
  await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
  await coordinator.getByRole('button', { name: 'Approve and start planning' }).click()
  await expect(coordinator).toHaveURL(/\/coordinator\/events\/[0-9a-f-]{36}/)
  const eventId = coordinator.url().split('/events/')[1].split(/[/?#]/)[0]
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await coordinator.getByPlaceholder('Search by address or details').fill(venueName)
  await coordinator.getByRole('button', { name: new RegExp(venueName) }).first().click()
  await coordinator.getByRole('button', { name: `Request ${venueName}` }).click()
  // Wait for each save to land before the next role acts (Week 9: wait for a
  // condition). Without these, 1 run in 6 opened the coordinator's page
  // before Venue Staff's approval was saved, so the event was not yet ready.
  await expect(coordinator.getByText(`Booking request for ${venueName} sent to Venue Staff`)).toBeVisible()
  const token = await apiToken(request, 'ec1')
  const bookings = await apiGet<{ bookingId: string }[]>(request, token, `/api/events/${eventId}/venue-bookings`)
  const venueStaff = await signedIn(browser, 'vs1')
  await venueStaff.goto(`/venue-staff/bookings/${bookings[0].bookingId}`)
  await venueStaff.getByRole('button', { name: 'Approve Booking' }).click()
  await venueStaff.getByRole('button', { name: 'Confirm approval' }).click()
  await expect(venueStaff.getByRole('button', { name: 'Confirm approval' })).toHaveCount(0)
  await expect.poll(async () => (await apiGet<{ status: string }[]>(request, token,
    `/api/events/${eventId}/venue-bookings`))[0].status).toBe('approved')
  await coordinator.goto(`/coordinator/events/${eventId}`)
  await coordinator.getByRole('button', { name: 'Send for safety review' }).click()
  await expect(coordinator.getByText(/Sent for the Operational Safety Check/)).toBeVisible()

  const officer = await signedIn(browser, 'so1')
  await test.step('SO-C6 AC3, AC5: the Safety Officer requests changes with a reason and the checklist item concerned', async () => {
    await officer.goto(`/safety-officer/events/${eventId}`)
    const requestChanges = officer.getByRole('button', { name: 'Request changes' })
    await officer.getByLabel('Note for the Event Coordinator').fill('Add a second exit marshal.')
    await expect(requestChanges).toBeDisabled()
    await officer.getByRole('group', { name: 'Checklist items this decision concerns' })
      .getByRole('checkbox', { name: /^Raise: Safety, emergency/ }).check()
    await requestChanges.click()
    await expect(officer.getByText('Changes requested', { exact: true })).toBeVisible()
  })

  await test.step('EC08 AC23, SO-C6 AC11: the coordinator sees Safety Changes Requested, the feedback and the item concerned', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await expect(coordinator.getByText('Safety Changes Requested').first()).toBeVisible()
    await expect(coordinator.getByText('Add a second exit marshal.').first()).toBeVisible()
    await expect(coordinator.locator('.safety-decision').getByRole('list', { name: 'Checklist items concerned' }))
      .toHaveText('Safety, emergency, and on-site operational arrangements are ready')
    await expect(coordinator.locator('.readiness-item--flagged')).toHaveCount(1)
  })

  await test.step('EC08 AC24: after addressing it the coordinator can send it again', async () => {
    const panel = coordinator.getByRole('region', { name: 'Ready for confirmation?' })
    await panel.getByRole('button', { name: 'Send for safety review' }).click()
    await expect(coordinator.getByText('Awaiting Safety Review').first()).toBeVisible()
  })
})
