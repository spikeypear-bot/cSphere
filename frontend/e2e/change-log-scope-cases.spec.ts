// @stories EC-NEW5 ECL-C7
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiToken, approvedBookingByApi, assignedByApi, createVenue, planningEventWithNeeds, signedIn, submittedRequestByApi, uniqueDay,
  uniqueName,
} from './support.ts'

// Sprint 3 functional test cases for EC-NEW5 (TC2–TC4) and ECL-C7 (TC1, TC2,
// TC4), from docs/test-cases/sprint3-ecnew5.md and sprint3-eclc7.md. EC-NEW5-TC1
// is in golden-path.spec.ts and ECL-C7-TC3 in access-scope.spec.ts.

const auth = async (request: APIRequestContext, username: string) =>
  ({ Authorization: `Bearer ${await apiToken(request, username)}` })

async function planning(request: APIRequestContext, prefix: string) {
  const day = uniqueDay()
  const eventName = uniqueName(prefix)
  return { ...(await planningEventWithNeeds(request, eventName, `${day}T09:00:00+08:00`, `${day}T12:00:00+08:00`)), eventName, day }
}

test('EC-NEW5 (happy): TC2 Major changes are labelled in words and can be shown on their own', async ({ browser, request }) => {
  const e = await planning(request, 'Change Log Gala')
  const venueId = await createVenue(request, uniqueName('Harbour Hall'))
  const booked = await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: await auth(request, 'ec1'), data: { venueId } })
  expect((await request.patch(`/api/venue-bookings/${(await booked.json()).bookingId}/reject`, { headers: await auth(request, 'vs1'),
    data: { reason: 'Closed for works', alternativeVenueId: null, alternativeArrangement: null } })).ok()).toBeTruthy()
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  const log = coordinator.getByRole('region', { name: 'Change log' })
  const filter = log.getByRole('group', { name: 'Filter the change log' })
  await expect(log.getByText('Major', { exact: true }).first()).toBeVisible()
  await filter.getByRole('button', { name: /^Major only/ }).click()
  const entries = log.getByRole('list', { name: 'Request timeline' }).locator(':scope > li')
  const total = await entries.count()
  expect(total).toBeGreaterThan(0)
  for (let i = 0; i < total; i++) await expect(entries.nth(i).getByText('Major', { exact: true })).toBeVisible()
  await filter.getByRole('button', { name: /^All changes/ }).click()
  expect(await entries.count()).toBeGreaterThan(total)
  await expect(log.getByRole('link', { name: 'Jump to latest' })).toBeVisible()
})

test('EC-NEW5 (failure): TC3 a refused action adds no entry, entries offer no edit or delete, and a load failure offers Try again', async ({ browser, request }) => {
  const e = await planning(request, 'Locked Gala')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto(`/coordinator/events/${e.eventId}`)
  const entries = coordinator.getByRole('region', { name: 'Change log' }).getByRole('list', { name: 'Request timeline' }).locator(':scope > li')
  await expect(entries.first()).toBeVisible()
  const before = await entries.count()
  expect((await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: await auth(request, 'ec2'),
    data: { venueId: await createVenue(request, uniqueName('Refused Hall')) } })).ok()).toBeFalsy()
  await coordinator.reload()
  await expect(entries.first()).toBeVisible()
  expect(await entries.count()).toBe(before)
  await expect(coordinator.getByRole('region', { name: 'Change log' }).getByRole('button', { name: /Edit|Delete|Revert/ })).toHaveCount(0)

  await coordinator.route('**/api/event-requests/*/timeline', (route) => route.abort())
  await coordinator.route(`**/api/events/${e.eventId}/timeline`, (route) => route.abort())
  await coordinator.reload()
  await expect(coordinator.getByText('The change log could not be loaded.')).toBeVisible()
  await expect(coordinator.getByRole('heading', { level: 1 })).toBeVisible()
  await coordinator.unroute('**/api/event-requests/*/timeline')
  await coordinator.unroute(`**/api/events/${e.eventId}/timeline`)
  await coordinator.getByRole('region', { name: 'Change log' }).getByRole('button', { name: 'Try again' }).click()
  await expect(entries.first()).toBeVisible()
})

test('EC-NEW5 (cross-cutting): TC4 the organiser sees only what concerns them; another organisation and another coordinator cannot open the log', async ({ browser, request }) => {
  const e = await planning(request, 'Audience Gala')
  const venueId = await createVenue(request, uniqueName('Audience Hall'))
  await approvedBookingByApi(request, e.eventId, venueId)
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${e.eventId}`)
  const log = organiser.getByRole('region', { name: 'Change log' })
  await expect(log).toContainText('submitted the request')
  await expect(log).not.toContainText('approved a venue booking')
  for (const username of ['eo3', 'ec2']) {
    for (const path of [`/api/event-requests/${e.requestId}/timeline`, `/api/events/${e.eventId}/timeline`]) {
      expect((await request.get(path, { headers: await auth(request, username) })).ok(), `${username} ${path}`).toBeFalsy()
    }
  }
})

test('ECL-C7 (happy): TC1 a coordinator\'s lists hold only their own work', async ({ browser, request }) => {
  const mine = await planning(request, 'Scope Mine Gala')
  const theirsId = await submittedRequestByApi(request, uniqueName('Scope Theirs Gala'), '2027-08-20T09:00:00+08:00', '2027-08-20T12:00:00+08:00')
  await assignedByApi(request, theirsId, 'ec2')
  const coordinator = await signedIn(browser, 'ec1')
  await coordinator.goto('/coordinator/my-events')
  await expect(coordinator.getByRole('heading', { name: mine.eventName })).toBeVisible()
  const ec = await auth(request, 'ec1')
  const assigned = await (await request.get('/api/events/assigned', { headers: ec })).json() as { coordinatorName: string | null }[]
  expect(assigned.every((ev) => ev.coordinatorName === null || ev.coordinatorName === 'ec1')).toBeTruthy()
  const queue = await (await request.get('/api/event-requests/queue', { headers: ec })).json() as
    { needsReview: { requestId: string }[]; awaitingOrganiser: { requestId: string }[] }
  expect([...queue.needsReview, ...queue.awaitingOrganiser].some((r) => r.requestId === theirsId)).toBeFalsy()
})

test('ECL-C7 (negative): TC2, TC4 another coordinator is refused with nothing revealed; the Lead can view but not act as the coordinator', async ({ browser, request }) => {
  const e = await planning(request, 'Scope Gala')
  const ec2 = await auth(request, 'ec2')
  const reads = [`/api/events/${e.eventId}`, `/api/events/${e.eventId}/venue-bookings`, `/api/events/${e.eventId}/readiness`,
    `/api/event-requests/${e.requestId}/review`]
  for (const path of reads) {
    const refused = await request.get(path, { headers: ec2 })
    expect(refused.status(), path).toBe(403)
    const body = await refused.text()
    expect(body, path).not.toContain(e.eventName)
    expect(body, path).not.toContain('Acme')
  }
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: ec2 })).status()).toBe(403)

  const lead = await signedIn(browser, 'ecl1')
  await lead.goto(`/coordinator-lead/events/${e.eventId}`)
  await expect(lead.getByText(e.eventName).first()).toBeVisible()
  await expect(lead.getByRole('button', { name: /Send for safety review|Confirm event/ })).toHaveCount(0)
  const ecl = await auth(request, 'ecl1')
  expect((await request.post(`/api/events/${e.eventId}/venue-bookings`, { headers: ecl,
    data: { venueId: await createVenue(request, uniqueName('Lead Hall')) } })).ok()).toBeFalsy()
  expect((await request.post(`/api/events/${e.eventId}/safety-review`, { headers: ecl })).ok()).toBeFalsy()
  expect((await request.post(`/api/events/${e.eventId}/confirm`, { headers: ecl })).ok()).toBeFalsy()
})
