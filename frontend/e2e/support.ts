import { expect, type APIRequestContext, type Browser, type Locator, type Page } from '@playwright/test'

// Shared helpers for the browser journeys (IS212 Week 9). Decisions are made
// in the browser, as each role would; the API is used only to create test
// data that is not the journey under test (a venue) and to look up IDs the
// way a user would follow a link.

export const PASSWORD = '123456'

/** A unique, visible name per run, so reruns never depend on old data. */
export function uniqueName(prefix: string) {
  return `${prefix} ${Date.now().toString(36)}`
}

/** One browser context (own cookies/storage) per role, signed in. */
export async function signedIn(browser: Browser, username: string): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible()
  return page
}

export async function apiToken(request: APIRequestContext, username: string): Promise<string> {
  const response = await request.post('/api/auth/login', { data: { username, password: PASSWORD } })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).accessToken as string
}

export async function apiGet<T>(request: APIRequestContext, token: string, path: string): Promise<T> {
  const response = await request.get(path, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.ok(), `GET ${path} -> ${response.status()}`).toBeTruthy()
  return (await response.json()) as T
}

/** Test data, not the journey: a venue open all week that fits the event. */
export async function createVenue(request: APIRequestContext, name: string): Promise<string> {
  const token = await apiToken(request, 'vs1')
  const response = await request.post('/api/venues', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      venueAddress: name,
      venueCapacity: 300,
      supportedLayouts: ['theatre'],
      operatingInformation: 'Loading bay at the back.',
      additionalInformation: null,
      venueAccessibilities: ['step_free_access'],
      venueFacilities: ['projection'],
      operatingHours: [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, openTime: '00:00', closeTime: '23:59' })),
    },
  })
  expect(response.ok(), `create venue -> ${response.status()} ${await response.text()}`).toBeTruthy()
  return (await response.json()).venueId as string
}

/** A competing pending request is fixture data; the hold decisions remain browser actions. */
export async function createCompetingBooking(request: APIRequestContext, venueId: string, eventName: string,
  startDatetime: string, endDatetime: string): Promise<string> {
  const organiser = await apiToken(request, 'eo1')
  const lead = await apiToken(request, 'ecl1')
  const coordinator = await apiToken(request, 'ec1')
  const post = async (path: string, token: string, data?: object) => {
    const result = await request.post(path, { headers: { Authorization: `Bearer ${token}` }, data })
    expect(result.ok(), `${path}: ${result.status()} ${await result.text()}`).toBeTruthy()
    return result.json()
  }
  const draft = await post('/api/event-requests', organiser, { eventName, purpose: 'Competing request fixture',
    startDatetime, endDatetime, expectedAttendance: 80, venueRequirements: 'Theatre seating',
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false })
  await post(`/api/event-requests/${draft.requestId}/submit`, organiser)
  const people = await apiGet<{ userId: string; username: string }[]>(request, lead, '/api/event-requests/unassigned/coordinators')
  const assigned = people.find(person => person.username === 'ec1')!
  await post(`/api/event-requests/unassigned/${draft.requestId}/assign`, lead, { coordinatorUserId: assigned.userId })
  const event = await post(`/api/event-requests/${draft.requestId}/approve`, coordinator)
  const booking = await post(`/api/events/${event.eventId}/venue-bookings`, coordinator, { venueId, startDatetime, endDatetime })
  return booking.bookingId
}

/** EO01/EO02: the organiser fills in and submits a complete request in the wizard.
 *
 * "Next" saves the draft to the server before it moves on, so after each one
 * the test waits for the next step to be on screen. Clicking "Next" twice
 * without waiting was flaky (1 run in 18): when a save was slow the second
 * click landed on the same step again (IS212 Week 9: wait for a condition,
 * never a duration). */
export async function submitRequest(page: Page, eventName: string, start: string, end: string) {
  const next = async (nextStepField: Locator) => {
    await page.getByRole('button', { name: 'Next' }).click()
    await expect(nextStepField).toBeVisible()
  }
  await page.goto('/organiser/requests/new')
  await page.getByLabel('Event name').fill(eventName)
  await page.getByLabel('Purpose').fill('Quarterly all-hands update')
  await next(page.getByLabel('Start date & time'))
  await page.getByLabel('Start date & time').fill(start)
  await page.getByLabel('End date & time').fill(end)
  await page.getByLabel('Expected attendance').fill('150')
  await next(page.getByLabel('Venue requirements'))
  await page.getByLabel('Venue requirements').fill('Theatre seating for 150')
  await page.getByRole('group', { name: 'Accessibility needs' })
    .getByRole('button', { name: 'No accessibility requirements needed' }).click()
  await next(page.getByRole('group', { name: 'Equipment requirements' }))
  await next(page.getByRole('list', { name: 'Required fields checklist' }))
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByText('Your event request has been submitted successfully.')).toBeVisible()
}

export async function requestIdOf(request: APIRequestContext, eventName: string): Promise<string> {
  const token = await apiToken(request, 'eo1')
  const requests = await apiGet<{ requestId: string; eventName: string }[]>(request, token, '/api/event-requests')
  const found = requests.find((r) => r.eventName === eventName)
  expect(found, `request "${eventName}" not found`).toBeTruthy()
  return found!.requestId
}

/** ECL-C3/ELC-C6: the Lead assigns the request to ec1. */
export async function leadAssigns(page: Page, requestId: string) {
  await page.goto(`/coordinator-lead/unassigned-requests/${requestId}`)
  await page.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Assign' }).click()
  await page.getByLabel('Event Coordinator').selectOption({ label: 'ec1' })
  await page.getByRole('button', { name: 'Assign request' }).click()
  await expect(page.getByText(/assigned to ec1/i).first()).toBeVisible()
}
