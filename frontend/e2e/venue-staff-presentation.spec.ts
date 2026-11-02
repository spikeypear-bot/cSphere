// @stories VS17 VS07 VS06A VS06B VS15 VS16 VS18 VS02
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, signedIn, uniqueName } from './support.ts'

// Two Venue Staff journeys. Reporting an operational issue's times in
// Singapore time is VenueOperationalIssuesPanel.test.tsx (VS13): it needs no backend.

test('VS06A VS07 VS18 (happy): Venue Staff add a venue, are told what is missing, then edit its hours and return to where they started', async ({ browser }) => {
  test.setTimeout(120_000)
  const staff = await signedIn(browser, 'vs1')
  await staff.goto('/venue-staff')
  await expect(staff.getByRole('heading', { level: 1, name: /^Good \w+, vs1$/ })).toBeVisible()
  await staff.getByRole('navigation', { name: 'Venue Staff' }).getByRole('link', { name: 'Venue catalogue' }).click()
  const main = staff.locator('main')
  const createdName = uniqueName('Staff form hall')
  const card = staff.locator('.venue-card-preview').filter({ has: staff.getByRole('heading', { name: createdName, exact: true }) })

  await test.step('VS06A AC3, AC4 (negative): saving without operating days is refused and says why', async () => {
    await main.getByRole('link', { name: /Add a venue/ }).click()
    await staff.getByRole('button', { name: 'Save venue' }).click()
    await expect(staff.getByText('Select at least one operating day.')).toBeVisible()
  })

  await test.step('VS06A AC1, AC5, AC7 / VS18: the venue is saved, confirmed, and listed with its hours', async () => {
    await staff.getByLabel('Venue name', { exact: true }).fill(createdName)
    await staff.getByLabel('Venue address', { exact: true }).fill(createdName)
    await staff.getByRole('button', { name: 'Theatre', exact: true }).click()
    await staff.getByRole('checkbox', { name: 'Monday', exact: true }).check()
    await staff.getByLabel('Monday opens').fill('09:00')
    await staff.getByLabel('Monday closes').fill('18:00')
    await staff.getByRole('button', { name: 'Save venue' }).click()
    await expect(staff.getByText('Venue saved successfully.')).toBeVisible()
    await expect(card.getByText('9am – 6pm', { exact: true })).toBeVisible()
  })

  const detailsLink = card.getByRole('link', { name: 'View venue details' })
  const detailUrl = await detailsLink.getAttribute('href')
  await test.step('VS06A AC6 / VS18: the venue record opens on its own page', async () => {
    expect(detailUrl).toMatch(/^\/venue-staff\/catalogue\/[^/]+$/)
    await detailsLink.click()
    await expect(staff).toHaveURL(detailUrl!)
    await expect(staff.getByRole('heading', { name: 'Venue details', exact: true })).toBeVisible()
  })

  await test.step('VS07: editing from the record returns to the record, showing the new hours', async () => {
    await card.getByRole('link', { name: 'Edit venue', exact: true }).click()
    await staff.getByLabel('Monday opens').fill('10:30')
    await staff.getByLabel('Monday closes').fill('23:30')
    await staff.getByRole('button', { name: 'Save venue', exact: true }).click()
    await expect(staff).toHaveURL(detailUrl!)
    await expect(staff.getByText('10:30am – 11:30pm', { exact: true })).toBeVisible()
  })

  await test.step('VS07: editing from the catalogue returns to the catalogue, showing the new hours', async () => {
    await staff.getByRole('link', { name: 'Back to venue catalogue', exact: true }).click()
    await card.getByRole('link', { name: 'Edit venue', exact: true }).click()
    await staff.getByLabel('Monday closes').fill('22:30')
    await staff.getByRole('button', { name: 'Save venue', exact: true }).click()
    await expect(staff).toHaveURL(/\/venue-staff\/catalogue$/)
    await expect(card.getByText('10:30am – 10:30pm', { exact: true })).toBeVisible()
    await expect(card.getByLabel('Operating information', { exact: true })).toHaveCount(0)
  })
  await staff.context().close()
})

test('VS17 VS15 VS16 (happy): lowering a venue\'s capacity flags its confirmed booking as needing attention in the catalogue, the assessment and the calendar, without changing the booking', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const venueName = uniqueName('Attention display hall')
  const venueId = await createVenue(request, venueName)
  // The browser clock is pinned to the month before this day, so "Next month" always shows it, whatever the real date.
  const day = '2099-03-15'
  const pinnedNow = new Date('2099-02-10T10:00:00+08:00')
  const eventId = await confirmedEventByApi(request, uniqueName('Staff display event'), venueId, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)
  const vs = await apiToken(request, 'vs1')
  const ec = await apiToken(request, 'ec1')
  const bookingId = (await apiGet<{ bookingId: string }[]>(request, ec, `/api/events/${eventId}/venue-bookings`))[0].bookingId
  const before = await apiGet<{ timing: unknown }>(request, vs, `/api/venue-bookings/${bookingId}`)
  const changed = await request.put(`/api/venues/${venueId}`, { headers: { Authorization: `Bearer ${vs}` }, data: { venueCapacity: 100 } })
  expect(changed.ok()).toBeTruthy()
  const staff = await signedIn(browser, 'vs1')

  await test.step('VS17 / VS18: the venue record lists the booking as Requires attention', async () => {
    await staff.goto(`/venue-staff/catalogue/${venueId}`)
    await expect(staff.getByRole('region', { name: 'Associated bookings' }).getByText('Requires attention', { exact: true })).toBeVisible()
  })

  await test.step('VS17 / VS16: the booking asks for a reassessment, and is not presented as a venue unavailability', async () => {
    await staff.goto(`/venue-staff/bookings/${bookingId}`)
    const assessment = staff.getByRole('article', { name: 'Booking suitability assessment' })
    await expect(assessment.getByRole('button', { name: 'Restore approval' })).toBeDisabled()
    await expect(staff.getByText(/This booking is affected by venue unavailability/)).toHaveCount(0)
    await staff.setViewportSize({ width: 390, height: 844 })
    await expect(assessment.getByLabel('Reassessment comment')).toBeVisible()
    expect(await staff.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  })

  await test.step('VS15 / VS17: the calendar shows it as Requires attention and links back to the booking', async () => {
    await staff.clock.setFixedTime(pinnedNow)
    await staff.goto('/venue-staff/availability-calendar')
    await staff.getByLabel('Venue', { exact: true }).selectOption(venueId)
    await staff.getByRole('button', { name: 'Next month', exact: true }).click()
    const entry = staff.getByRole('button', { name: /Requires attention.*Event:/ }).first()
    await expect(entry).toBeVisible()
    await expect(entry).toHaveAttribute('data-booking-status', 'attention')
    await entry.click()
    await expect(staff.getByRole('dialog').getByText('Requires attention', { exact: true })).toBeVisible()
    await expect(staff.getByRole('dialog').getByRole('link', { name: 'View booking details' })).toHaveAttribute('href', `/venue-staff/bookings/${bookingId}`)
  })

  await test.step('VS17: the booking itself is flagged but its times are unchanged', async () => {
    const after = await apiGet<{ status: string; timing: unknown }>(request, vs, `/api/venue-bookings/${bookingId}`)
    expect(after.status).toBe('attention')
    expect(after.timing).toEqual(before.timing)
  })
  await staff.context().close()
})
