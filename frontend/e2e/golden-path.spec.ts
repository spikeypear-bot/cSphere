// @stories EO01 EO02 ECL-C3 ELC-C6 EC02 EC03 VS02 VS03 EC08 SO-C3 SO-C4 EC-NEW2 EO04 EO09 EC-NEW5
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, createVenue, leadAssigns, requestIdOf, signedIn, submitRequest, uniqueName } from './support.ts'

// IS212 Week 9: the one journey that matters most, end to end through the
// browser across six roles: the core workflow the project instructions call
// "coherent end-to-end event workflows". Each step names the stories and
// acceptance criteria it checks; expected values come from those ACs, not
// from what the page happened to show when it was recorded.

test('golden path: a request becomes a confirmed event the organiser can rely on', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('E2E Town Hall')
  const venueName = uniqueName('E2E Marina Hall')
  await createVenue(request, venueName)

  const organiser = await signedIn(browser, 'eo1')
  await test.step('EO01/EO02: the organiser submits a complete request', async () => {
    await submitRequest(organiser, eventName, '2027-06-14T09:00', '2027-06-14T12:00')
  })
  const requestId = await requestIdOf(request, eventName)

  await test.step('EC-NEW2 AC5: before assignment the organiser sees Submitted', async () => {
    await organiser.reload()
    await expect(organiser.getByRole('listitem').filter({ hasText: eventName }).getByText('Submitted').first()).toBeVisible()
  })

  const lead = await signedIn(browser, 'ecl1')
  await test.step('ECL-C3/ELC-C6: the Lead assigns the request to ec1', async () => {
    await leadAssigns(lead, requestId)
  })

  const coordinator = await signedIn(browser, 'ec1')
  let eventId = ''
  await test.step('EC02, EC08 AC38: the coordinator approves; the event is in Planning (EC-NEW2 AC8)', async () => {
    await coordinator.goto(`/coordinator/requests/${requestId}`)
    await coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
    await coordinator.getByRole('button', { name: 'Approve and start planning' }).click()
    await expect(coordinator).toHaveURL(/\/coordinator\/events\/[0-9a-f-]{36}/)
    eventId = coordinator.url().split('/events/')[1].split(/[/?#]/)[0]
    await expect(coordinator.getByText('Planning').first()).toBeVisible()
  })

  await test.step('EC03, EC08 AC38: the coordinator requests the venue (EC01–EC03 still work alongside EC08)', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
    await coordinator.getByPlaceholder('Search by address or details').fill(venueName)
    await coordinator.getByRole('button', { name: new RegExp(venueName) }).first().click()
    await coordinator.getByRole('button', { name: `Request ${venueName}` }).click()
    await expect(coordinator.getByText(`Booking request for ${venueName} sent to Venue Staff`)).toBeVisible()
  })

  await test.step('EC08 AC8, AC15: a pending booking keeps the event from being sent', async () => {
    const panel = coordinator.getByRole('region', { name: 'Ready for confirmation?' })
    // Shown twice on purpose: as the venue item's detail (AC3) and in the
    // "Not ready yet" list of every blocker (AC15).
    await expect(panel.getByText('1 booking is still waiting for Venue Staff.')).toHaveCount(2)
    await expect(panel.getByText('Not ready yet')).toBeVisible()
    await expect(panel.getByRole('button', { name: 'Send for safety review' })).toHaveCount(0)
  })

  const venueStaff = await signedIn(browser, 'vs1')
  await test.step('VS02/VS03: Venue Staff approve the booking', async () => {
    const token = await apiToken(request, 'ec1')
    const bookings = await apiGet<{ bookingId: string }[]>(request, token, `/api/events/${eventId}/venue-bookings`)
    await venueStaff.goto(`/venue-staff/bookings/${bookings[0].bookingId}`)
    await venueStaff.getByRole('button', { name: 'Approve Booking' }).click()
    await venueStaff.getByRole('button', { name: 'Confirm approval' }).click()
    // Wait until the approval is saved before the coordinator acts on it.
    await expect(venueStaff.getByRole('button', { name: 'Confirm approval' })).toHaveCount(0)
    await expect.poll(async () => (await apiGet<{ status: string }[]>(request, token,
      `/api/events/${eventId}/venue-bookings`))[0].status).toBe('approved')
  })

  await test.step('EC08 AC7, AC16: with the venue approved the coordinator sends the event for its safety check', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    const panel = coordinator.getByRole('region', { name: 'Ready for confirmation?' })
    await panel.getByRole('button', { name: 'Send for safety review' }).click()
    await expect(panel.getByText(/Sent for the Operational Safety Check/)).toBeVisible()
    await expect(coordinator.getByText('Awaiting Safety Review').first()).toBeVisible()
  })

  const officer = await signedIn(browser, 'so1')
  await test.step('EC08 AC18, SO-C1: the event is in the Safety Officer\'s queue', async () => {
    await officer.goto('/safety-officer')
    await expect(officer.getByText(eventName)).toBeVisible()
  })

  await test.step('SO-C3/SO-C4: the Safety Officer completes the checklist and approves', async () => {
    await officer.goto(`/safety-officer/events/${eventId}`)
    const boxes = officer.getByRole('checkbox')
    await expect(boxes).toHaveCount(6)
    for (let i = 0; i < 6; i++) {
      // The box shows the saved state, so it ticks once the server has stored
      // it: click, then wait for that (Week 9: wait for a condition).
      await boxes.nth(i).click()
      await expect(boxes.nth(i)).toBeChecked()
      await expect(officer.getByText(`${i + 1} of 6 items complete`)).toBeVisible()
    }
    await officer.getByRole('button', { name: 'Approve' }).click()
    await expect(officer.getByText('Current approval: approved')).toBeVisible()
  })

  await test.step('EC08 AC25, AC29: the coordinator confirms; the event is Confirmed (EC-NEW2 AC12)', async () => {
    await coordinator.goto(`/coordinator/events/${eventId}`)
    await coordinator.getByRole('button', { name: 'Confirm event' }).click()
    await expect(coordinator.getByText(/Event confirmed\. The organiser and the staff involved have been notified\./)).toBeVisible()
    await expect(coordinator.getByText('Confirmed').first()).toBeVisible()
  })

  await test.step('EC-NEW5 AC1–AC6: the change log records who did what, oldest first, across the roles', async () => {
    await coordinator.reload()
    const log = coordinator.getByRole('region', { name: 'Change log' }).getByRole('list', { name: 'Request timeline' })
    const entries = log.locator('.activity-timeline__entry')
    await expect(entries.first()).toContainText('eo1 (Event Organiser) submitted the request')
    await expect(log).toContainText('vs1 (Venue Staff) approved a venue booking')
    await expect(log).toContainText(venueName)
    await expect(entries.last()).toContainText('ec1 (Event Coordinator) confirmed the event')
  })

  await test.step('EC08 AC31, EO04 AC5: the organiser is notified, and the notification opens their arrangements', async () => {
    await organiser.goto('/organiser')
    await organiser.getByRole('button', { name: /Notifications, \d+ unread/ }).click()
    await organiser.getByText(`"${eventName}" is now Confirmed`).first().click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/events/${eventId}`))
  })

  await test.step('EO04 AC9, AC10, AC12, AC16, AC17: the organiser sees the confirmed arrangements', async () => {
    const card = organiser.getByRole('region', { name: 'Confirmed arrangements' })
    await expect(card.getByText(venueName)).toBeVisible()
    await expect(card).toContainText('(Singapore time)')
    await expect(card).toContainText('150 expected')
    await expect(card.getByText('Safety check approved')).toBeVisible()
    await expect(card.getByRole('link', { name: 'Contact ec1' })).toBeVisible()
  })

  await test.step('EC-NEW5 AC31: the organiser\'s change log leaves out internal venue decisions', async () => {
    const log = organiser.getByRole('region', { name: 'Change log' })
    await expect(log).toContainText('confirmed the event')
    await expect(log).not.toContainText('approved a venue booking')
  })

  await test.step('EO04 AC26: printing gives a clean summary without navigation or buttons', async () => {
    await organiser.emulateMedia({ media: 'print' })
    await expect(organiser.getByRole('button', { name: 'Print summary' })).toBeHidden()
    await expect(organiser.getByRole('button', { name: 'Log out' })).toBeHidden()
    await expect(organiser.getByRole('region', { name: 'Confirmed arrangements' }).getByText(venueName)).toBeVisible()
    await organiser.emulateMedia({ media: 'screen' })
  })

  await test.step('EO04 AC27: the page works on a phone-sized screen without sideways scrolling', async () => {
    await organiser.setViewportSize({ width: 375, height: 667 })
    await expect(organiser.getByRole('region', { name: 'Confirmed arrangements' }).getByText(venueName)).toBeVisible()
    const overflow = await organiser.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow).toBeLessThanOrEqual(0)
  })
})
