// @stories EC03 VS02
import { expect, test, type APIRequestContext } from '@playwright/test'
import { apiGet, apiToken, assignedByApi, createVenue, openBell, signedIn, uniqueName } from './support.ts'

// Sprint 2 functional test cases for EC03 (Sheet: Test Cases - Sprint 2,
// EC03-TC1…TC15): the coordinator requests a venue for an event in Planning.
// Choosing and requesting happen in the browser; the refusals the page cannot
// reach (it never offers an unavailable venue) are checked through the API,
// as the test cases do. Every venue and event is unique to the run.

type Booking = { bookingId: string; venueId: string; status: string; startDatetime: string; endDatetime: string
  bookingNotes: string | null }

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

/** An event in Planning coordinated by ec1, with the needs a case is about. */
async function planningEvent(request: APIRequestContext, prefix: string, start: string, end: string,
  needs: { expectedAttendance?: number; requiredFacilities?: string[]; accessibilityNeeds?: string[] } = {}) {
  const eventName = uniqueName(prefix)
  const eo = await auth(request, 'eo1')
  const draft = await request.post('/api/event-requests', { headers: eo, data: { eventName, purpose: 'Venue booking case',
    startDatetime: start, endDatetime: end, expectedAttendance: needs.expectedAttendance ?? 120,
    venueRequirements: 'Theatre seating', accessibilityNeeds: needs.accessibilityNeeds ?? ['none'],
    requiredFacilities: needs.requiredFacilities ?? [], registrationNeeds: false } })
  expect(draft.ok(), await draft.text()).toBeTruthy()
  const requestId = (await draft.json()).requestId as string
  expect((await request.post(`/api/event-requests/${requestId}/submit`, { headers: eo })).ok()).toBeTruthy()
  await assignedByApi(request, requestId)
  const approved = await request.post(`/api/event-requests/${requestId}/approve`, { headers: await auth(request, 'ec1') })
  expect(approved.ok(), await approved.text()).toBeTruthy()
  return { eventId: (await approved.json()).eventId as string, eventName }
}

async function book(request: APIRequestContext, eventId: string, data: Record<string, unknown>) {
  return request.post(`/api/events/${eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data })
}

async function bookingsOf(request: APIRequestContext, eventId: string): Promise<Booking[]> {
  return apiGet<Booking[]>(request, await apiToken(request, 'ec1'), `/api/events/${eventId}/venue-bookings`)
}

async function approve(request: APIRequestContext, bookingId: string) {
  const approved = await request.patch(`/api/venue-bookings/${bookingId}/approve`, { headers: await auth(request, 'vs1') })
  expect(approved.ok(), await approved.text()).toBeTruthy()
}

test('EC03 (boundary): TC1, TC2, TC3, TC15 attendance equal to capacity can be requested after comparing the venue with the event; the booking takes the event\'s times and Venue Staff are told', async ({ browser, request }) => {
  const hall = uniqueName('Raffles Conference Hall')
  const hallId = await createVenue(request, hall, 120, ['projection'])
  const { eventId, eventName } = await planningEvent(request, 'Acme Leadership Offsite', '2027-02-03T09:00:00+08:00',
    '2027-02-03T17:00:00+08:00', { expectedAttendance: 120, requiredFacilities: ['projection'] })
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await expect(coordinator.getByText('What the event needs')).toBeVisible()
  await coordinator.getByPlaceholder('Search by address or details').fill(hall)
  await coordinator.getByRole('button', { name: new RegExp(hall) }).first().click()
  const compare = coordinator.locator('.venue-request__compare')
  await expect(compare.getByRole('row', { name: /Capacity/ })).toContainText('120 ✓')
  await expect(compare.getByRole('row', { name: /Required facilities/ })).toContainText('All provided ✓')
  await coordinator.getByLabel('Notes for Venue Staff (optional)').fill('Please set up theatre style.')
  await coordinator.getByRole('button', { name: `Request ${hall}` }).click()
  await expect(coordinator.getByText(`Booking request for ${hall} sent to Venue Staff`)).toBeVisible()

  const booking = (await bookingsOf(request, eventId)).find((b) => b.venueId === hallId)!
  expect(booking.status).toBe('pending')
  expect(Date.parse(booking.startDatetime)).toBe(Date.parse('2027-02-03T09:00:00+08:00'))
  expect(Date.parse(booking.endDatetime)).toBe(Date.parse('2027-02-03T17:00:00+08:00'))
  expect(booking.bookingNotes).toBe('Please set up theatre style.')

  const staff = await signedIn(browser, 'vs1')
  await expect((await openBell(staff)).getByRole('listitem').filter({ hasText: `New venue booking request for "${eventName}"` })).toBeVisible()
  await staff.goto('/venue-staff/booking-approvals')
  await expect(staff.getByText(eventName).first()).toBeVisible()
})

test('EC03 (boundary): TC4 one more person than the venue holds: shown as not available with the reason, and refused', async ({ browser, request }) => {
  const hall = uniqueName('Raffles Conference Hall')
  const hallId = await createVenue(request, hall, 120)
  const { eventId } = await planningEvent(request, 'Acme Big Town Hall', '2027-02-04T09:00:00+08:00',
    '2027-02-04T12:00:00+08:00', { expectedAttendance: 121 })
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await coordinator.getByPlaceholder('Search by address or details').fill(hall)
  await coordinator.getByRole('checkbox', { name: 'Show unavailable venues' }).check()
  await coordinator.getByRole('button', { name: new RegExp(hall) }).first().click()
  await expect(coordinator.getByText('Capacity 120 is below the expected attendance of 121.').first()).toBeVisible()
  await expect(coordinator.getByRole('button', { name: `Request ${hall}` })).toBeDisabled()
  expect((await book(request, eventId, { venueId: hallId })).ok()).toBeFalsy()
})

test('EC03 (negative): TC5 a venue missing a required facility cannot be requested', async ({ request }) => {
  const hallId = await createVenue(request, uniqueName('Raffles Conference Hall'), 300, ['projection'])
  const { eventId } = await planningEvent(request, 'Acme Stage Gala', '2027-02-05T09:00:00+08:00',
    '2027-02-05T12:00:00+08:00', { requiredFacilities: ['stage'] })
  expect((await book(request, eventId, { venueId: hallId })).ok()).toBeFalsy()
  expect(await bookingsOf(request, eventId)).toHaveLength(0)
})

test('EC03 (boundary): TC6 a venue missing a requested accessibility feature can be requested only with a justification', async ({ request }) => {
  const studioId = await createVenue(request, uniqueName('Bugis Workshop Studio'), 300)
  const { eventId } = await planningEvent(request, 'Acme Accessible Workshop', '2027-02-06T09:00:00+08:00',
    '2027-02-06T12:00:00+08:00', { accessibilityNeeds: ['wheelchair_support'] })
  expect((await book(request, eventId, { venueId: studioId })).ok()).toBeFalsy()
  const justified = await book(request, eventId, { venueId: studioId,
    suitabilityNote: 'Step-free access throughout; staff will assist wheelchair users.' })
  expect(justified.ok(), await justified.text()).toBeTruthy()
  expect((await bookingsOf(request, eventId))[0].status).toBe('pending')
})

test('EC03 (conflict): TC7, TC8 an overlapping approved booking blocks the venue; one that ends exactly at the start does not', async ({ request }) => {
  const studioId = await createVenue(request, uniqueName('Bugis Workshop Studio'), 300)
  const existing = await planningEvent(request, 'Acme Existing Studio Booking', '2027-02-10T11:00:00+08:00', '2027-02-10T13:00:00+08:00')
  const existingBooking = await book(request, existing.eventId, { venueId: studioId })
  expect(existingBooking.ok()).toBeTruthy()
  await approve(request, (await existingBooking.json()).bookingId)

  const overlap = await planningEvent(request, 'Acme Overlap Seminar', '2027-02-10T10:00:00+08:00', '2027-02-10T12:00:00+08:00')
  expect((await book(request, overlap.eventId, { venueId: studioId })).ok()).toBeFalsy()
  const backToBack = await planningEvent(request, 'Acme Back To Back Session', '2027-02-10T13:00:00+08:00', '2027-02-10T15:00:00+08:00')
  const allowed = await book(request, backToBack.eventId, { venueId: studioId })
  expect(allowed.ok(), await allowed.text()).toBeTruthy()
})

test('EC03 (conflict): TC9 setup and turnaround time count as occupied', async ({ request }) => {
  const pavilionId = await createVenue(request, uniqueName('Changi Garden Pavilion'), 300)
  const tea = await planningEvent(request, 'Acme Afternoon Pavilion Tea', '2027-02-12T14:00:00+08:00', '2027-02-12T18:30:00+08:00')
  const teaBooking = await book(request, tea.eventId, { venueId: pavilionId, setupDurationMinutes: 60,
    turnaroundDurationMinutes: 60, timingNote: 'Catering needs an hour each side.' })
  expect(teaBooking.ok(), await teaBooking.text()).toBeTruthy()
  await approve(request, (await teaBooking.json()).bookingId)
  const dinner = await planningEvent(request, 'Acme Buffer Clash Dinner', '2027-02-12T19:00:00+08:00', '2027-02-12T22:00:00+08:00')
  expect((await book(request, dinner.eventId, { venueId: pavilionId })).ok()).toBeFalsy()
})

test('EC03 (negative): TC10 a venue with a recorded unavailable period is not available, with the period shown, and is refused', async ({ browser, request }) => {
  const room = uniqueName('Novena Meeting Room 2')
  const roomId = await createVenue(request, room, 300)
  const vs = await auth(request, 'vs1')
  const period = { startDateTime: '2027-02-15T08:00:00+08:00', endDateTime: '2027-02-15T18:00:00+08:00', reason: 'Carpet replacement' }
  const preview = await request.post(`/api/venues/${roomId}/unavailability/preview`, { headers: vs, data: period })
  expect(preview.ok(), await preview.text()).toBeTruthy()
  const saved = await request.post(`/api/venues/${roomId}/unavailability`, { headers: vs,
    data: { ...period, previewToken: (await preview.json()).previewToken, acknowledgedBookingIds: [] } })
  expect(saved.ok(), await saved.text()).toBeTruthy()
  const { eventId } = await planningEvent(request, 'Acme Unavailable Room Meeting', '2027-02-15T10:00:00+08:00', '2027-02-15T12:00:00+08:00')

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  await coordinator.getByPlaceholder('Search by address or details').fill(room)
  await coordinator.getByRole('checkbox', { name: 'Show unavailable venues' }).check()
  await coordinator.getByRole('button', { name: new RegExp(room) }).first().click()
  await expect(coordinator.getByText(/Carpet replacement/).first()).toBeVisible()
  await expect(coordinator.getByRole('button', { name: `Request ${room}` })).toBeDisabled()
  expect((await book(request, eventId, { venueId: roomId })).ok()).toBeFalsy()
})

test('EC03 (boundary): TC11 another event\'s pending request for the same venue and time does not block it', async ({ request }) => {
  const hallId = await createVenue(request, uniqueName('Raffles Conference Hall'), 300)
  const first = await planningEvent(request, 'Acme Competing Pending Event', '2027-02-20T10:00:00+08:00', '2027-02-20T12:00:00+08:00')
  expect((await book(request, first.eventId, { venueId: hallId })).ok()).toBeTruthy()
  const second = await planningEvent(request, 'Acme Pending Competition', '2027-02-20T10:00:00+08:00', '2027-02-20T12:00:00+08:00')
  const allowed = await book(request, second.eventId, { venueId: hallId })
  expect(allowed.ok(), await allowed.text()).toBeTruthy()
  expect((await bookingsOf(request, first.eventId))[0].status).toBe('pending')
  expect((await bookingsOf(request, second.eventId))[0].status).toBe('pending')
})

test('EC03 (conflict): TC12, TC13 the same venue cannot be requested twice while pending (another venue can, EC-C2); after a rejection it can be requested again', async ({ request }) => {
  const studioId = await createVenue(request, uniqueName('Bugis Workshop Studio'), 300)
  const hallId = await createVenue(request, uniqueName('Raffles Conference Hall'), 300)
  const { eventId } = await planningEvent(request, 'Acme Second Booking Event', '2027-02-22T09:00:00+08:00', '2027-02-22T12:00:00+08:00')
  const first = await book(request, eventId, { venueId: studioId })
  expect(first.ok()).toBeTruthy()
  expect((await book(request, eventId, { venueId: studioId })).ok()).toBeFalsy()
  expect((await book(request, eventId, { venueId: hallId })).ok()).toBeTruthy()

  const rejected = await request.patch(`/api/venue-bookings/${(await first.json()).bookingId}/reject`, {
    headers: await auth(request, 'vs1'), data: { reason: 'Being repainted', alternativeVenueId: null, alternativeArrangement: null } })
  expect(rejected.ok(), await rejected.text()).toBeTruthy()
  const again = await book(request, eventId, { venueId: studioId })
  expect(again.ok(), await again.text()).toBeTruthy()
  const studio = (await bookingsOf(request, eventId)).filter((b) => b.venueId === studioId).map((b) => b.status).sort()
  expect(studio).toEqual(['pending', 'rejected'])
})

test('EC03 (boundary): TC14 booking notes of 2,000 characters are accepted; 2,001 are refused', async ({ request }) => {
  const hallId = await createVenue(request, uniqueName('Raffles Conference Hall'), 300)
  const { eventId } = await planningEvent(request, 'Acme Notes Limit Event', '2027-02-24T09:00:00+08:00', '2027-02-24T12:00:00+08:00')
  expect((await book(request, eventId, { venueId: hallId, bookingNotes: 'x'.repeat(2001) })).ok()).toBeFalsy()
  const accepted = await book(request, eventId, { venueId: hallId, bookingNotes: 'y'.repeat(2000) })
  expect(accepted.ok(), await accepted.text()).toBeTruthy()
})
