// @stories VS17 VS07 EO04 EC03
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, confirmedEventByApi, createVenue, signedIn, uniqueName } from './support.ts'

test('VS17 (cross-cutting): the same operating hours update live for organiser, coordinator and venue staff', async ({ browser, request }, testInfo) => {
 test.setTimeout(120_000)
 const venueId = await createVenue(request, uniqueName('Operating hours hall'))
 const eventId = await confirmedEventByApi(request, uniqueName('Operating hours event'), venueId, '2027-11-20T09:00:00+08:00', '2027-11-20T12:00:00+08:00')
 const vs = await apiToken(request, 'vs1'); const ec = await apiToken(request, 'ec1')
 const bookings = await apiGet<{ bookingId: string }[]>(request, ec, `/api/events/${eventId}/venue-bookings`)
 const bookingId = bookings[0].bookingId
 const before = await apiGet<{ timing: unknown }>(request, vs, `/api/venue-bookings/${bookingId}`)
 const staff = await signedIn(browser, 'vs1'); const coordinator = await signedIn(browser, 'ec1'); const organiser = await signedIn(browser, 'eo1')
 await staff.goto(`/venue-staff/bookings/${bookingId}`); await coordinator.goto(`/coordinator/events/${eventId}`); await organiser.goto(`/organiser/events/${eventId}`)
 await expect(organiser.getByRole('heading', { name: 'Confirmed arrangements' })).toBeVisible()
 // The coordinator's event page keeps a booking's hours in its details window; it stays open and updates live.
 await coordinator.getByRole('button', { name: 'View venue details' }).first().click()
 for (const [openTime, closeTime, expectedStatus] of [['08:00','18:00','approved'], ['10:00','11:00','attention']]) {
  if (openTime === '08:00') {
   // Exercise the real edit form and return navigation from booking details.
   await staff.getByRole('link', {name:'Edit venue',exact:true}).click()
   await expect(staff.getByLabel('Saturday opens')).toBeVisible()
   await expect(staff.getByLabel('Operating information',{exact:true})).toHaveCount(0)
   for (const day of ['Monday','Tuesday','Wednesday','Thursday','Friday','Sunday']) {
    await staff.getByRole('checkbox',{name:day,exact:true}).uncheck()
   }
   await staff.getByLabel('Saturday opens').fill(openTime)
   await staff.getByLabel('Saturday closes').fill(closeTime)
   const response = staff.waitForResponse(r=>r.url().endsWith(`/api/venues/${venueId}`) && r.request().method()==='PUT')
   await staff.getByRole('button',{name:'Save venue',exact:true}).click()
   const saved=await response
   expect(saved.ok(),await saved.text()).toBeTruthy()
   expect(saved.request().postDataJSON()).toEqual({operatingHours:[{dayOfWeek:6,openTime,closeTime}]})
   await expect(staff).toHaveURL(new RegExp(`/venue-staff/bookings/${bookingId}$`))
  } else {
   const saved = await request.put(`/api/venues/${venueId}`, { headers: { Authorization: `Bearer ${vs}` }, data: { operatingHours: [{ dayOfWeek: 6, openTime, closeTime }] } })
   expect(saved.ok(), await saved.text()).toBeTruthy()
  }
  const time = openTime === '08:00' ? '8am \u2013 6pm' : '10am \u2013 11am'
  for (const page of [staff, coordinator, organiser]) {
   const hours = page.getByRole('list', { name: 'Weekly operating hours' })
   await expect(hours.getByText('Saturday', { exact: true })).toBeVisible({ timeout: 20_000 })
   await expect(hours.getByText(time, { exact: true })).toBeVisible({ timeout: 20_000 })
  }
  if (openTime === '08:00') {
   await staff.screenshot({ path: testInfo.outputPath('staff-hours.png'), fullPage: true })
   await coordinator.screenshot({ path: testInfo.outputPath('coordinator-hours.png'), fullPage: true })
   await organiser.screenshot({ path: testInfo.outputPath('organiser-hours.png'), fullPage: true })
  }
  const after = await apiGet<{ status: string; timing: unknown }>(request, vs, `/api/venue-bookings/${bookingId}`)
  expect(after.status).toBe(expectedStatus); expect(after.timing).toEqual(before.timing)
 }
 await expect(organiser.getByText('Arrangements are being updated.', { exact: true })).toBeVisible()
 await staff.context().close(); await coordinator.context().close(); await organiser.context().close()
})
