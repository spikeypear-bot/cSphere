// @stories DEV16 EC04
import { expect, test, type Page } from '@playwright/test'

async function openFixture(page: Page, role: 'venue-staff' | 'coordinator' = 'venue-staff', venueCount = 1) {
  await page.addInitScript(role => localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'fixture', refreshToken: 'fixture', userId: 'fixture', username: 'fixture',
    role, organisation: 'ConnectSphere',
  })), role)
  const venue = {
    venueId: 'room', venueName: 'Singapore room', venueAddress: 'Singapore', venueCapacity: 100,
    supportedLayouts: ['theatre'], venueFacilities: ['projection'], venueAccessibilities: [],
    operatingInformation: '', additionalInformation: null, latitude: 1.3521, longitude: 103.8198,
  }
  const venues = Array.from({ length: venueCount }, (_, i) => i === 0 ? venue : ({ ...venue,
    venueId: `room-${i}`, venueName: `Singapore room ${i + 1}`,
    venueAddress: `Community centre, Room ${i + 1}, Level 3, Singapore`,
  }))
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    const json = path === '/api/venues' ? venues
      : path.includes('/map/activity') ? [{ venueId: 'room', eventCount: 1, pendingBookingCount: 0, matchingBookingCount: 1 }]
      : path.includes('/map/venues/room/bookings') ? [{ bookingId: 'booking', eventId: 'event', eventName: 'Community workshop', status: 'approved',
        startDatetime: '2030-05-01T01:00:00Z', endDatetime: '2030-05-01T03:00:00Z', holdExpiresAt: null, serverTime: '2030-04-01T00:00:00Z', past: false }]
      : []
    return route.fulfill({ json })
  })
  await page.route('https://tile.openstreetmap.org/**', route => route.fulfill({ contentType: 'image/png',
    body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==', 'base64') }))
  await page.goto(role === 'coordinator' ? '/coordinator/venue-search' : '/venue-staff')
}

async function renderedZoom(page: Page) {
  // The next gesture starts only once Leaflet finishes the previous zoom transition.
  if (await page.locator('.leaflet-zoom-anim').count()) return -1
  return page.locator('.leaflet-tile-container').evaluateAll(containers => {
    const current = containers.filter(c => c.querySelector('img')).sort((a,b) => Number(getComputedStyle(b).zIndex) - Number(getComputedStyle(a).zIndex))[0]
    const src = current?.querySelector('img')?.getAttribute('src')
    return src ? Number(new URL(src).pathname.split('/')[1]) : -1
  })
}

test('DEV16 (boundary): desktop catalogue aligns with the full map card through wrapping, tile errors and mobile switching', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await openFixture(page, 'venue-staff', 30)
  const list = page.getByRole('list', { name: 'Map venue catalogue' })
  const map = page.getByRole('region', { name: 'Interactive venue map' })
  await expect(map).toBeVisible()
  await expect(list.getByRole('button')).toHaveCount(30)
  const aligned = async () => {
    const left = await list.boundingBox(), right = await map.boundingBox()
    return left && right ? Math.max(Math.abs(left.y - right.y), Math.abs(left.y + left.height - right.y - right.height)) : Infinity
  }
  await expect.poll(aligned).toBeLessThanOrEqual(1)
  expect(await list.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
  await list.evaluate(el => { el.scrollTop = el.scrollHeight })
  const last = await list.getByRole('button').last().boundingBox(), pane = await list.boundingBox()
  expect(last!.y).toBeGreaterThanOrEqual(pane!.y)
  expect(last!.y + last!.height).toBeLessThanOrEqual(pane!.y + pane!.height)

  // Larger text wraps the map footer; no matching fixed-height calculation is needed.
  await page.evaluate(() => { document.documentElement.style.fontSize = '20px' })
  await expect.poll(aligned).toBeLessThanOrEqual(1)
  await page.route('https://tile.openstreetmap.org/**', route => route.abort())
  await map.getByRole('button', { name: 'Zoom in', exact: true }).click()
  await expect(map.getByText(/Map tiles could not load/)).toBeVisible()
  await expect.poll(aligned).toBeLessThanOrEqual(1)

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(list).toBeVisible()
  expect((await list.boundingBox())!.height).toBeGreaterThan(0)
  await page.getByRole('button', { name: 'Map view', exact: true }).click()
  await expect(map).toBeVisible()
  await expect(list).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await expect(list).toBeVisible()
  await expect.poll(aligned).toBeLessThanOrEqual(1)
})

test('DEV16 EC04 (cross-cutting): desktop margins stay consistent and map controls and details match Venue Staff pages', async ({ page, context }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await openFixture(page)
  await expect(page.getByRole('button', { name: /Singapore room Singapore/ })).toBeVisible()
  const staffShell = await page.locator('.app-shell__content').boundingBox()
  expect(staffShell!.width).toBeCloseTo(900, 1)
  expect(staffShell!.x).toBeCloseTo(270, 1)
  const staffStyle = await page.getByLabel('Minimum capacity').evaluate(el => {
    const style = getComputedStyle(el)
    return [style.backgroundColor, style.borderRadius, style.paddingTop, style.fontWeight]
  })
  await expect(page.getByLabel('Minimum capacity')).toHaveAttribute('placeholder', 'e.g. 100')
  const placeholderColor = await page.getByLabel('Minimum capacity').evaluate(el => getComputedStyle(el, '::placeholder').color)
  await expect(page.locator('.venue-map-marker')).toHaveText('1')
  expect(await page.locator('.venue-map-explorer').evaluate(el => getComputedStyle(el).gap))
    .toBe(await page.locator('.venue-staff-home').evaluate(el => getComputedStyle(el).gap))
  const filterPadding = await page.locator('.venue-map-filters').evaluate(el => getComputedStyle(el).paddingTop)
  await expect(page.getByRole('group', { name: 'Required facilities', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('venue-map-desktop-spacing.png'), fullPage: true, animations: 'disabled' })
  await page.locator('.venue-map-card').click()
  const details = page.getByRole('region', { name: 'Selected venue details' })
  await expect(details.getByText('Community workshop')).toBeVisible()
  const venueStyle = await details.locator('.venue-card-preview').evaluate(el => {
    const style = getComputedStyle(el)
    return [style.backgroundColor, style.borderRadius, style.paddingTop, style.borderColor, style.boxShadow]
  })
  await expect(details.getByText('No additional information recorded.')).toBeVisible()
  await expect(details.getByText(/distinct|persisted|seed|test record|modelled/i)).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('venue-map-selected-details.png'), fullPage: true, animations: 'disabled' })
  await page.goto('/venue-staff/catalogue')
  expect(await page.locator('.venue-card-preview').evaluate(el => {
    const style = getComputedStyle(el)
    return [style.backgroundColor, style.borderRadius, style.paddingTop, style.borderColor, style.boxShadow]
  })).toEqual(venueStyle)
  await page.goto('/venue-staff/catalogue/new')
  expect(await page.getByLabel('Venue name').evaluate(el => {
    const style = getComputedStyle(el)
    return [style.backgroundColor, style.borderRadius, style.paddingTop, style.fontWeight]
  })).toEqual(staffStyle)
  expect(await page.getByLabel('Venue name').evaluate(el => getComputedStyle(el, '::placeholder').color)).toBe(placeholderColor)
  expect(await page.locator('.venue-form > .card').evaluate(el => getComputedStyle(el).paddingTop)).toBe(filterPadding)
  const coordinator = await context.newPage()
  await coordinator.setViewportSize({ width: 1440, height: 1000 })
  await openFixture(coordinator, 'coordinator')
  await expect(coordinator.getByRole('heading', { name: 'Search venues' })).toBeVisible()
  const coordinatorShell = await coordinator.locator('.app-shell__content').boundingBox()
  expect(coordinatorShell!.x).toBeCloseTo(staffShell!.x, 1)
  expect(coordinatorShell!.width).toBeCloseTo(staffShell!.width, 1)
  await expect(coordinator.getByLabel('Required capacity', { exact: true })).toBeVisible()
  await coordinator.close()
})

test('DEV16 (happy): touchpad pinch wheel events zoom the hovered map in and out without zooming the page', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await openFixture(page)
  const map = page.locator('.leaflet-container')
  await expect(map).toBeVisible()
  await expect.poll(() => renderedZoom(page)).toBe(11)
  await map.scrollIntoViewIfNeeded()
  const box = await map.boundingBox()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  const pixelRatio = await page.evaluate(() => window.devicePixelRatio)
  await page.keyboard.down('Control')
  await page.mouse.wheel(0, -120)
  await page.keyboard.up('Control')
  await expect.poll(() => renderedZoom(page)).toBeGreaterThan(11)
  expect(await page.evaluate(() => window.devicePixelRatio)).toBe(pixelRatio)
  await page.keyboard.down('Control')
  await page.mouse.wheel(0, 120)
  await page.keyboard.up('Control')
  await expect.poll(() => renderedZoom(page)).toBe(11)
  expect(await page.evaluate(() => window.devicePixelRatio)).toBe(pixelRatio)
})

test.describe('DEV16 touchscreen gestures', () => {
  test.use({ hasTouch: true, viewport: { width: 390, height: 844 } })
  test('DEV16 (happy): two-finger pinch zooms the mobile map in and out', async ({ page, context }, testInfo) => {
    await openFixture(page)
    await page.getByRole('button', { name: 'Map view', exact: true }).click()
    const map = page.locator('.leaflet-container')
    await map.scrollIntoViewIfNeeded()
    await expect.poll(() => renderedZoom(page)).toBe(11)
    const box = await map.boundingBox()
    const x = box!.x + box!.width / 2, y = box!.y + box!.height / 2
    const cdp = await context.newCDPSession(page)
    const points = (radius: number) => [{ x: x-radius, y, id: 0 }, { x: x+radius, y, id: 1 }]
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: points(35) })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: points(100) })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await expect.poll(() => renderedZoom(page)).toBeGreaterThan(11)
    const zoomed = await renderedZoom(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: points(100) })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: points(35) })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await expect.poll(() => renderedZoom(page)).toBeLessThan(zoomed)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('venue-map-mobile-spacing.png'), fullPage: true, animations: 'disabled' })
    await cdp.detach()
  })
})
