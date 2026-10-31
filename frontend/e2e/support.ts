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
export async function createVenue(request: APIRequestContext, name: string,
  venueCapacity = 300, venueFacilities: string[] = ['projection']): Promise<string> {
  const token = await apiToken(request, 'vs1')
  const response = await request.post('/api/venues', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      venueAddress: name,
      venueCapacity,
      supportedLayouts: ['theatre'],
      operatingInformation: 'Loading bay at the back.',
      additionalInformation: null,
      venueAccessibilities: ['step_free_access'],
      venueFacilities,
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

/** Test data, not the journey: an event of eo1's coordinated by ec1, taken all
 * the way to Confirmed through the API (request, assignment, approval, venue
 * booking and its approval, safety review with a completed checklist,
 * confirmation). Each step is the real endpoint each role would use, so the
 * event is genuinely Confirmed. Returns the event's id. */
export async function confirmedEventByApi(request: APIRequestContext, eventName: string, venueId: string,
  startDatetime: string, endDatetime: string,
  registrationSettings?: { registrationCapacity: number; registrationOpensAt: string }): Promise<string> {
  const tokens = {
    eo: await apiToken(request, 'eo1'), ecl: await apiToken(request, 'ecl1'), ec: await apiToken(request, 'ec1'),
    vs: await apiToken(request, 'vs1'), so: await apiToken(request, 'so1'),
  }
  const call = async (method: 'post' | 'patch', path: string, token: string, data?: object) => {
    const result = await request[method](path, { headers: { Authorization: `Bearer ${token}` }, data: data ?? {} })
    expect(result.ok(), `${method.toUpperCase()} ${path}: ${result.status()} ${await result.text()}`).toBeTruthy()
    return result.json()
  }
  const draft = await call('post', '/api/event-requests', tokens.eo, { eventName, purpose: 'Cancellation journey',
    startDatetime, endDatetime, expectedAttendance: 120, venueRequirements: 'Theatre seating',
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: !!registrationSettings, ...registrationSettings })
  await call('post', `/api/event-requests/${draft.requestId}/submit`, tokens.eo)
  const people = await apiGet<{ userId: string; username: string }[]>(request, tokens.ecl,
    '/api/event-requests/unassigned/coordinators')
  const ec1 = people.find((person) => person.username === 'ec1')!
  await call('post', `/api/event-requests/unassigned/${draft.requestId}/assign`, tokens.ecl, { coordinatorUserId: ec1.userId })
  const event = await call('post', `/api/event-requests/${draft.requestId}/approve`, tokens.ec)
  const booking = await call('post', `/api/events/${event.eventId}/venue-bookings`, tokens.ec, { venueId })
  await call('patch', `/api/venue-bookings/${booking.bookingId}/approve`, tokens.vs)
  await call('post', `/api/events/${event.eventId}/safety-review`, tokens.ec)
  const readiness = await apiGet<{ items: { itemKey: string }[] }>(request, tokens.so,
    `/api/safety/events/${event.eventId}/readiness`)
  for (const item of readiness.items) {
    await call('patch', `/api/safety/events/${event.eventId}/readiness/items/${item.itemKey}`, tokens.so, { checked: true })
  }
  await call('post', `/api/safety/events/${event.eventId}/approval`, tokens.so, { decision: 'approve' })
  await call('post', `/api/events/${event.eventId}/confirm`, tokens.ec)
  return event.eventId as string
}

/** Test data: a request of eo1's (Acme Pte Ltd) submitted through the API, as
 * the organiser's own wizard would. Returns its id. */
export async function submittedRequestByApi(request: APIRequestContext, eventName: string,
  startDatetime: string, endDatetime: string, expectedAttendance = 150): Promise<string> {
  const headers = { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }
  const draft = await request.post('/api/event-requests', { headers, data: { eventName, purpose: 'E2E journey',
    startDatetime, endDatetime, expectedAttendance, venueRequirements: 'Theatre seating',
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false } })
  expect(draft.ok(), `create request: ${draft.status()} ${await draft.text()}`).toBeTruthy()
  const requestId = (await draft.json()).requestId as string
  const submitted = await request.post(`/api/event-requests/${requestId}/submit`, { headers })
  expect(submitted.ok(), `submit request: ${submitted.status()} ${await submitted.text()}`).toBeTruthy()
  return requestId
}

/** Test data: the Lead assigns a request to a coordinator through the API. */
export async function assignedByApi(request: APIRequestContext, requestId: string, coordinator = 'ec1') {
  const token = await apiToken(request, 'ecl1')
  const people = await apiGet<{ userId: string; username: string }[]>(request, token,
    '/api/event-requests/unassigned/coordinators')
  const result = await request.post(`/api/event-requests/unassigned/${requestId}/assign`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { coordinatorUserId: people.find((p) => p.username === coordinator)!.userId } })
  expect(result.ok(), `assign: ${result.status()} ${await result.text()}`).toBeTruthy()
}

/** Test data: a request submitted, assigned to ec1 and approved, so it is an
 * event in Planning. Returns the event's id. */
export async function planningEventByApi(request: APIRequestContext, eventName: string,
  startDatetime: string, endDatetime: string): Promise<string> {
  const requestId = await submittedRequestByApi(request, eventName, startDatetime, endDatetime)
  await assignedByApi(request, requestId)
  const approved = await request.post(`/api/event-requests/${requestId}/approve`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` } })
  expect(approved.ok(), `approve: ${approved.status()} ${await approved.text()}`).toBeTruthy()
  return (await approved.json()).eventId as string
}

/** Opens the signed-in user's notification bell; returns the panel. */
export async function openBell(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: /^Notifications/ }).click()
  return page.locator('.notification-bell__panel')
}

/** Test data: an event in Planning with one approved venue booking, sent by
 * ec1 for safety review (Awaiting Safety Review). Returns the event's id. */
export async function awaitingSafetyReviewByApi(request: APIRequestContext, eventName: string, venueId: string,
  startDatetime: string, endDatetime: string): Promise<string> {
  const eventId = await planningEventByApi(request, eventName, startDatetime, endDatetime)
  const ec = { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }
  const booking = await request.post(`/api/events/${eventId}/venue-bookings`, { headers: ec, data: { venueId } })
  expect(booking.ok(), `book venue: ${booking.status()} ${await booking.text()}`).toBeTruthy()
  const approved = await request.patch(`/api/venue-bookings/${(await booking.json()).bookingId}/approve`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'vs1')}` } })
  expect(approved.ok(), `approve booking: ${approved.status()} ${await approved.text()}`).toBeTruthy()
  const sent = await request.post(`/api/events/${eventId}/safety-review`, { headers: ec })
  expect(sent.ok(), `send for safety review: ${sent.status()} ${await sent.text()}`).toBeTruthy()
  return eventId
}

/** A date (YYYY-MM-DD) in 2028–2029 that differs from run to run, for test
 * data that shares finite stock such as seeded equipment, so reruns never
 * compete with the reservations an earlier run left behind. */
export function uniqueDay(): string {
  const day = new Date(Date.UTC(2028, 0, 1) + (Date.now() % 700) * 86_400_000)
  return day.toISOString().slice(0, 10)
}

/** Functional test-case evidence: a full-page screenshot named after the
 * Sheet's test case ID (e.g. "EO03-TC1a"). Only taken when E2E_EVIDENCE_DIR
 * is set, so CI runs are unchanged. */
export async function evidence(page: Page, id: string) {
  const dir = process.env.E2E_EVIDENCE_DIR
  if (dir) await page.screenshot({ path: `${dir}/${id}.png`, fullPage: true })
}

/** The request behind an event of eo1's, as the organiser's own list shows it. */
export async function requestOfEvent(request: APIRequestContext, eventId: string): Promise<string> {
  const token = await apiToken(request, 'eo1')
  const requests = await apiGet<{ requestId: string; eventId: string | null }[]>(request, token, '/api/event-requests')
  const found = requests.find((r) => r.eventId === eventId)
  expect(found, `request of event ${eventId} not found`).toBeTruthy()
  return found!.requestId
}

/** Test data: eo1 asks for a change through the API, starting from the
 * request's current details exactly as the EO03 form does. Returns its id. */
export async function changeRequestByApi(request: APIRequestContext, requestId: string,
  patch: Record<string, unknown>, reason = 'Circumstances changed'): Promise<string> {
  const headers = { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }
  const r = await apiGet<Record<string, unknown>>(request, headers.Authorization.slice(7), `/api/event-requests/${requestId}`)
  const details = {
    eventName: r.eventName, purpose: r.purpose, description: r.description,
    startDatetime: r.startDatetime, endDatetime: r.endDatetime, expectedAttendance: r.expectedAttendance,
    venueRequirements: r.venueRequirements, equipmentRequirements: r.equipmentRequirements,
    accessibilityNeeds: r.accessibilityNeeds ?? [], requiredFacilities: r.requiredFacilities ?? [],
    registrationNeeds: r.registrationNeeds, registrationCapacity: r.registrationCapacity ?? null,
    registrationOpensAt: r.registrationOpensAt ?? null, attendeeInstructions: r.attendeeInstructions ?? null,
    ...patch,
  }
  const sent = await request.post('/api/change-requests', { headers, data: { requestId, reason, details } })
  expect(sent.ok(), `change request: ${sent.status()} ${await sent.text()}`).toBeTruthy()
  return (await sent.json()).changeRequestId as string
}

/** Test data: ec1 books a venue for the whole event and vs1 approves it. Returns the booking's id. */
export async function approvedBookingByApi(request: APIRequestContext, eventId: string, venueId: string): Promise<string> {
  const booking = await request.post(`/api/events/${eventId}/venue-bookings`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` }, data: { venueId } })
  expect(booking.ok(), `book venue: ${booking.status()} ${await booking.text()}`).toBeTruthy()
  const bookingId = (await booking.json()).bookingId as string
  const approved = await request.patch(`/api/venue-bookings/${bookingId}/approve`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'vs1')}` } })
  expect(approved.ok(), `approve booking: ${approved.status()} ${await approved.text()}`).toBeTruthy()
  return bookingId
}

const bearer = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

async function okJson(response: import('@playwright/test').APIResponse, what: string) {
  expect(response.ok(), `${what}: ${response.status()} ${await response.text()}`).toBeTruthy()
  return response.json()
}

/** Test data: eo1's request with the needs a case is about, submitted,
 * assigned to ec1 and approved, so it is an event in Planning. */
export async function planningEventWithNeeds(request: APIRequestContext, eventName: string, startDatetime: string,
  endDatetime: string, needs: { expectedAttendance?: number; requiredFacilities?: string[]; accessibilityNeeds?: string[]
    equipmentRequirements?: string | null } = {}): Promise<{ eventId: string; requestId: string }> {
  const eo = await bearer(request, 'eo1')
  const draft = await okJson(await request.post('/api/event-requests', { headers: eo, data: { eventName, purpose: 'E2E journey',
    startDatetime, endDatetime, expectedAttendance: needs.expectedAttendance ?? 120, venueRequirements: 'Theatre seating',
    equipmentRequirements: needs.equipmentRequirements ?? null, accessibilityNeeds: needs.accessibilityNeeds ?? ['none'],
    requiredFacilities: needs.requiredFacilities ?? [], registrationNeeds: false } }), 'create request')
  await okJson(await request.post(`/api/event-requests/${draft.requestId}/submit`, { headers: eo }), 'submit')
  await assignedByApi(request, draft.requestId)
  const event = await okJson(await request.post(`/api/event-requests/${draft.requestId}/approve`,
    { headers: await bearer(request, 'ec1') }), 'approve')
  return { eventId: event.eventId as string, requestId: draft.requestId as string }
}

/** Test data: ec1 asks for `quantity` of a catalogue item, ts1 reserves and approves it. */
export async function approvedEquipmentByApi(request: APIRequestContext, eventId: string, start: string, end: string,
  equipmentName = 'Projection Screen', quantity = 2): Promise<string> {
  const catalogue = await apiGet<{ equipmentId: string; equipmentName: string }[]>(request, await apiToken(request, 'ec1'),
    '/api/equipment/catalogue')
  const item = catalogue.find((e) => e.equipmentName === equipmentName)!
  const created = await okJson(await request.post(`/api/events/${eventId}/equipment-request`, { headers: await bearer(request, 'ec1'),
    data: { technicalRequirement: `${quantity} × ${equipmentName}.`, items: [{ equipmentId: item.equipmentId, quantity }] } }),
  'request equipment')
  const ts = await bearer(request, 'ts1')
  await okJson(await request.post('/api/equipment/reservations', { headers: ts,
    data: { eventId, equipmentId: item.equipmentId, quantity, start, end } }), 'reserve equipment')
  await okJson(await request.patch(`/api/equipment-requests/${created.requestId}/status`, { headers: ts,
    data: { status: 'approved' } }), 'approve equipment')
  return created.requestId as string
}

/** Test data: ec1 sends the event for its safety check; so1 ticks every item and approves. */
export async function safetyApprovedByApi(request: APIRequestContext, eventId: string) {
  await okJson(await request.post(`/api/events/${eventId}/safety-review`, { headers: await bearer(request, 'ec1') }), 'send')
  const so = await bearer(request, 'so1')
  const readiness = await apiGet<{ items: { itemKey: string }[] }>(request, so.Authorization.slice(7),
    `/api/safety/events/${eventId}/readiness`)
  for (const item of readiness.items) {
    await okJson(await request.patch(`/api/safety/events/${eventId}/readiness/items/${item.itemKey}`,
      { headers: so, data: { checked: true } }), 'tick')
  }
  await okJson(await request.post(`/api/safety/events/${eventId}/approval`, { headers: so, data: { decision: 'approve' } }), 'approve')
}

export async function confirmByApi(request: APIRequestContext, eventId: string) {
  await okJson(await request.post(`/api/events/${eventId}/confirm`, { headers: await bearer(request, 'ec1') }), 'confirm')
}

/** Test data (EO07): eo1 asks to cancel and ec1 approves, so the event is Cancelled. */
export async function cancelledByApi(request: APIRequestContext, eventId: string, requestId: string) {
  const asked = await okJson(await request.post(`/api/event-requests/${requestId}/cancellation-request`, {
    headers: await bearer(request, 'eo1'), data: { reason: 'The event is no longer needed.' } }), 'ask to cancel')
  await okJson(await request.post(`/api/events/${eventId}/cancellation-requests/${asked.cancellationRequestId}/approve`, {
    headers: await bearer(request, 'ec1'), data: {} }), 'approve cancellation')
}

/** Test data (VS-C1): vs1 records the venue unavailable, acknowledging every booking it affects. */
export async function venueUnavailableByApi(request: APIRequestContext, venueId: string, startDateTime: string,
  endDateTime: string, reason: string) {
  const vs = await bearer(request, 'vs1')
  const period = { startDateTime, endDateTime, reason }
  const preview = await okJson(await request.post(`/api/venues/${venueId}/unavailability/preview`, { headers: vs, data: period }), 'preview')
  await okJson(await request.post(`/api/venues/${venueId}/unavailability`, { headers: vs, data: { ...period,
    previewToken: preview.previewToken,
    acknowledgedBookingIds: preview.affectedBookings.map((b: { bookingId: string }) => b.bookingId) } }), 'record unavailability')
}
