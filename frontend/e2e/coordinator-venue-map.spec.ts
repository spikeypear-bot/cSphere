// @stories DEV16 EC03 EC04 EC-C2 EC10 AU04
import { expect, test, type Page } from '@playwright/test'
import { apiGet, apiToken, createCompetingBooking, createVenue, signedIn, uniqueName } from './support.ts'

const EVENT = 'b000864d-c587-448f-8ad6-d314de540d2c'
const event = { eventId: EVENT, eventName: 'Map workshop', purpose: 'Workshop', status: 'pending',
  startDatetime: '2031-05-05T02:00:00Z', endDatetime: '2031-05-05T04:00:00Z', expectedAttendance: 50,
  venueRequirements: 'Seating', accessibilityNeeds: [], requiredFacilities: [], equipmentRequirements: null }
const room = (id: string, name: string) => ({ venueId: id, venueName: name, venueAddress: `${name} address`,
  venueCapacity: 100, supportedLayouts: ['theatre'], venueAccessibilities: [], venueFacilities: [],
  operatingHours: [{ dayOfWeek: 1, openTime: '09:00', closeTime: '18:00' }], operatingInformation: '',
  additionalInformation: id === 'a' ? 'Courtyard entrance' : 'Garden entrance', latitude: 1.3, longitude: 103.8 })
const venues = [room('a', 'Alpha'), room('b', 'Beta'), { ...room('missing', 'Unlocated'), latitude: null }]
async function tiles(page: Page) {
  await page.route('https://tile.openstreetmap.org/**', route => route.fulfill({ contentType: 'image/png',
    body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==', 'base64') }))
}
async function fixture(page: Page, path: string) {
  await page.addInitScript(() => localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'fixture', refreshToken: 'fixture', userId: 'fixture', username: 'ec1', role: 'coordinator', organisation: 'ConnectSphere',
  })))
  const calls: string[] = []
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    calls.push(`${route.request().method()} ${url.pathname}`)
    const json = url.pathname === `/api/events/${EVENT}` ? event
      : url.pathname === `/api/events/${EVENT}/venue-options` ? venues.map(venue => ({ venue,
        verdict: 'suitable', capacityOk: true, spareCapacity: 50, missingAccessibility: [], conflicts: [], reasons: [] }))
      : url.pathname === '/api/venues/search' ? venues : []
    return route.fulfill({ json })
  })
  await tiles(page)
  await page.goto(path)
  return calls
}

test('DEV16 EC03 (cross-cutting): two-column request map shares filters and highlights only selected colocated rooms on desktop/mobile', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const calls = await fixture(page, `/coordinator/events/${EVENT}/venue-booking`)
  const map = page.getByRole('region', { name: 'Interactive venue map' })
  const list = page.getByRole('list', { name: 'Venues ranked for this event' })
  const marker = map.locator('.venue-map-marker')
  await expect(marker).toHaveCount(1)
  await expect(marker).toHaveText('2')
  const needs = await page.locator('.venue-request__needs').boundingBox()
  const mapBox = await map.boundingBox()
  const toolbar = await page.locator('.venue-request__toolbar').boundingBox()
  expect(mapBox!.x).toBeGreaterThan(needs!.x + needs!.width)
  expect(Math.abs(mapBox!.y - needs!.y)).toBeLessThanOrEqual(1)
  expect(toolbar!.y).toBeGreaterThanOrEqual(mapBox!.y + mapBox!.height)
  expect(await map.evaluate(element => getComputedStyle(element).padding)).toBe('0px')
  await list.getByRole('button', { name: /^Alpha address/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Add to request' }).click()
  await expect(marker).toHaveClass(/venue-map-marker--selected/)
  await expect(map.getByRole('checkbox', { name: 'Alpha', exact: true })).toBeChecked()
  await expect(map.getByRole('checkbox', { name: 'Beta', exact: true })).not.toBeChecked()
  await marker.click()
  await expect(map.getByRole('checkbox', { name: 'Alpha', exact: true })).toBeChecked()
  await map.getByRole('checkbox', { name: 'Beta', exact: true }).click()
  await expect(list.getByRole('button', { name: /^Beta address/ }).getByText('Added')).toBeVisible()
  await expect(map.getByRole('checkbox', { name: 'Alpha', exact: true })).toBeChecked()
  await expect(map.getByRole('checkbox', { name: 'Beta', exact: true })).toBeChecked()
  const search = page.getByRole('searchbox', { name: 'Search venues' })
  await search.fill('Beta')
  await expect(marker).toHaveText('1')
  await expect(marker).toHaveClass(/venue-map-marker--selected/)
  await search.fill('no matching venue')
  await expect(marker).toHaveCount(0)
  await expect(page.getByRole('list', { name: 'Selected venues' }).getByRole('listitem')).toHaveCount(2)
  await search.fill('')
  await expect(marker).toHaveText('2')
  await expect(marker).toHaveClass(/venue-map-marker--selected/)
  const review = page.getByRole('list', { name: 'Selected venues' })
  await review.getByRole('listitem').filter({ has: page.locator('strong', { hasText: 'Beta address' }) }).getByRole('button', { name: 'Remove selection' }).click()
  await marker.click()
  await expect(map.getByRole('checkbox', { name: 'Alpha', exact: true })).toBeChecked()
  await expect(map.getByRole('checkbox', { name: 'Beta', exact: true })).not.toBeChecked()
  await map.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('ec-map-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await map.scrollIntoViewIfNeeded()
  await expect(map).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect((await map.boundingBox())!.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('ec-map-mobile.png'), fullPage: true })
  expect(calls.some(call => !call.startsWith('GET ') || call.includes('/venue-staff/'))).toBe(false)
  expect(errors).toEqual([])
})

test('DEV16 EC04 (boundary): search map mirrors available results and removes every marker for an empty text match', async ({ page }) => {
  const calls = await fixture(page, '/coordinator/venue-search')
  const map = page.getByRole('region', { name: 'Interactive venue map' })
  await expect(map.locator('.venue-map-marker')).toHaveCount(0)
  await page.getByLabel('Event starts').fill('2031-05-05T10:00')
  await page.getByLabel('Event ends').fill('2031-05-05T12:00')
  await page.getByRole('button', { name: 'Search venues', exact: true }).click()
  await expect(map.locator('.venue-map-marker')).toHaveText('2')
  await page.getByRole('searchbox', { name: 'Search by address or details' }).fill('courtyard')
  await expect(map.locator('.venue-map-marker')).toHaveText('1')
  await expect(page.getByRole('button', { name: 'Alpha address' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Beta address' })).toHaveCount(0)
  // "Show on map" sits in the venue's details window and closes it.
  await page.getByRole('button', { name: 'Alpha address' }).click()
  await page.getByRole('dialog', { name: 'Alpha address' }).getByRole('button', { name: 'Show on map' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(map.locator('.venue-map-marker')).toHaveClass(/venue-map-marker--selected/)
  await map.locator('.venue-map-marker').click()
  await expect(page.getByRole('button', { name: 'Alpha address' })).toHaveAttribute('data-map-focused', 'true')
  await page.getByRole('searchbox', { name: 'Search by address or details' }).fill('unmatched')
  await expect(map.locator('.venue-map-marker')).toHaveCount(0)
  await expect(page.getByText('No matching venues found.')).toBeVisible()
  expect(calls.filter(call => call.includes('/venues/search'))).toHaveLength(1)
  expect(calls.some(call => call.includes('/venue-staff/'))).toBe(false)
})

test('DEV16 EC03 AU04 (happy): an assigned coordinator requests a persisted mapped venue through the existing booking API', async ({ browser, request }) => {
  test.setTimeout(90_000)
  const fixtureVenue = await createVenue(request, uniqueName('EC map fixture'))
  const existing = await createCompetingBooking(request, fixtureVenue, uniqueName('EC mapped event'),
    '2031-05-05T10:00:00+08:00', '2031-05-05T12:00:00+08:00')
  const staffToken = await apiToken(request, 'vs1'), ecToken = await apiToken(request, 'ec1')
  const details = await apiGet<{ event: { eventId: string } }>(request, staffToken, `/api/venue-bookings/${existing}`)
  const eventId = details.event.eventId
  const options = await apiGet<{ verdict: string; venue: { venueId: string; venueName: string; venueAddress: string;
    latitude: number | null; longitude: number | null } }[]>(request, ecToken, `/api/events/${eventId}/venue-options`)
  const chosen = options.find(option => option.verdict === 'suitable' && option.venue.latitude != null && option.venue.longitude != null)!
  expect(chosen, 'persisted suitable venue with coordinates').toBeTruthy()
  const page = await signedIn(browser, 'ec1')
  const paths: string[] = []
  page.on('request', request => paths.push(new URL(request.url()).pathname))
  await tiles(page)
  await page.goto(`/coordinator/events/${eventId}/venue-booking`)
  const list = page.getByRole('list', { name: 'Venues ranked for this event' })
  await list.getByRole('button', { name: new RegExp('^' + chosen.venue.venueAddress.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Add to request' }).click()
  const map = page.getByRole('region', { name: 'Interactive venue map' })
  await expect(map.locator('.venue-map-marker--selected')).toHaveCount(1)
  await expect(map.getByRole('checkbox', { name: chosen.venue.venueName!, exact: true })).toBeChecked()
  await page.getByRole('button', { name: `Request ${chosen.venue.venueAddress}`, exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/coordinator/events/${eventId}$`))
  const saved = await apiGet<{ venueId: string; status: string }[]>(request, ecToken, `/api/events/${eventId}/venue-bookings`)
  expect(saved).toHaveLength(2)
  expect(saved.find(booking => booking.venueId === chosen.venue.venueId)?.status).toBe('pending')
  expect(paths.some(path => path.startsWith('/api/venue-staff/') || path.startsWith('/api/venue-bookings/'))).toBe(false)
  await page.context().close()
})
