// @stories VS17
import { expect, test } from '@playwright/test'
import { apiGet, apiToken, createCompetingBooking, createVenue, signedIn, uniqueName } from './support.ts'

test('VS17 (happy): staff follow a read notification, review changed requirements and the assigned coordinator sees the outcome', async ({ browser, request }) => {
  test.setTimeout(120_000)
  // The existing approval and approved-change workflows are fixture data here.
  // The journey under test is the staff review and its coordinator integration.
  const name = uniqueName('VS17 browser event')
  const venueName = uniqueName('VS17 browser hall')
  const venueId = await createVenue(request, venueName)
  const bookingId = await createCompetingBooking(request, venueId, name, '2027-10-20T09:00:00+08:00', '2027-10-20T12:00:00+08:00')
  const staffToken = await apiToken(request, 'vs1')
  const coordinatorToken = await apiToken(request, 'ec1')
  const organiserToken = await apiToken(request, 'eo1')
  const before = await apiGet<{ status: string; event: { eventId: string }; timing: unknown }>(request, staffToken, `/api/venue-bookings/${bookingId}`)
  const eventId = before.event.eventId
  const requests = await apiGet<{ requestId: string; eventId: string }[]>(request, organiserToken, '/api/event-requests')
  const requestId = requests.find(r => r.eventId === eventId)!.requestId
  const asked = await request.post('/api/change-requests', { headers: { Authorization: `Bearer ${organiserToken}` }, data: {
    requestId, reason: 'Attendance increased', details: { eventName: name, purpose: 'Competing request fixture',
      startDatetime: '2027-10-20T09:00:00+08:00', endDatetime: '2027-10-20T12:00:00+08:00', expectedAttendance: 120,
      venueRequirements: 'Theatre seating', accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false },
  } })
  expect(asked.ok(), await asked.text()).toBeTruthy()
  const { changeRequestId } = await asked.json() as { changeRequestId: string }
  const approval = await request.post(`/api/change-requests/${changeRequestId}/approve`, { headers: { Authorization: `Bearer ${coordinatorToken}` }, data: { acknowledged: [] } })
  expect(approval.ok(), await approval.text()).toBeTruthy()

  const staff = await signedIn(browser, 'vs1')
  await staff.getByRole('button', { name: /Notifications/ }).click()
  await staff.getByText(`Venue requirement review required for "${name}"`, { exact: true }).click()
  await expect(staff).toHaveURL(new RegExp(`/venue-staff/bookings/${bookingId}`))
  const panel = staff.getByRole('region', { name: 'Venue requirement reviews' })
  await expect(panel.getByText('Review Required', { exact: true })).toBeVisible()
  await expect(panel.getByRole('cell', { name: '80', exact: true })).toBeVisible()
  await expect(panel.getByRole('cell', { name: '120', exact: true })).toBeVisible()
  await expect.poll(async () => {
    const notifications = await apiGet<{ type: string; eventName: string; read: boolean }[]>(request, staffToken, '/api/notifications')
    return notifications.find(n => n.type === 'venue_requirement_changed' && n.eventName === name)?.read
  }).toBe(true)
  await expect(panel.getByText('Review Required', { exact: true })).toBeVisible()
  await panel.getByRole('combobox', { name: 'Outcome', exact: true }).selectOption('action_required')
  await panel.getByLabel('Review comment (required)').fill('Confirm the revised seating plan with the venue team.')
  await panel.getByRole('button', { name: 'Save review' }).click()
  await expect(panel.getByRole('heading', { name: `${venueName} — Coordinator action required`, exact: true })).toBeVisible()
  await expect(panel.getByText('Review Required', { exact: true })).toHaveCount(0)
  const after = await apiGet<{ status: string; timing: unknown }>(request, staffToken, `/api/venue-bookings/${bookingId}`)
  expect(after.status).toBe(before.status)
  expect(after.timing).toEqual(before.timing)

  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${eventId}/venue-booking`)
  const outcomes = coordinator.getByRole('region', { name: 'Venue requirement reviews' })
  await expect(outcomes.getByText('Confirm the revised seating plan with the venue team.', { exact: true })).toBeVisible()
  await expect(outcomes.getByRole('heading', { name: `${venueName} — Coordinator action required`, exact: true })).toBeVisible()
  await expect(outcomes.getByRole('button', { name: 'Save review' })).toHaveCount(0)
  const notifications = await apiGet<{ type: string; eventName: string; linkPath: string }[]>(request, coordinatorToken, '/api/notifications')
  expect(notifications.find(n => n.type === 'venue_requirement_reviewed' && n.eventName === name)?.linkPath).toBe(`/coordinator/events/${eventId}/venue-booking`)
  await staff.context().close(); await coordinator.context().close()
})
