// @stories EO03 EC-NEW4 EC-NEW2
import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiGet, apiToken, assignedByApi, changeRequestByApi, confirmedEventByApi, createVenue, evidence, openBell, planningEventByApi,
  requestOfEvent, signedIn, submittedRequestByApi, uniqueName,
} from './support.ts'

// EO03 functional test cases (Sheet: Test Cases - Sprint 4, EO03-TC1…TC7),
// each run in the browser against the real backend. Expected wording and
// values come from the EO03 acceptance criteria. Screenshots for the Sheet
// are saved only when E2E_EVIDENCE_DIR is set (support.ts `evidence`).

type Event = { expectedAttendance: number; eventName: string; journeyStatus: { code: string } }
type Change = { changeRequestId: string; status: string }

async function eventAs(request: APIRequestContext, eventId: string): Promise<Event> {
  return apiGet<Event>(request, await apiToken(request, 'eo1'), `/api/events/${eventId}`)
}

async function changesOf(request: APIRequestContext, requestId: string): Promise<Change[]> {
  return apiGet<Change[]>(request, await apiToken(request, 'eo1'), `/api/change-requests?requestId=${requestId}`)
}

test('EO03 AC2, AC8, AC9, AC22, AC23, AC24, AC25, AC28, AC29, AC30 (happy): the organiser asks to change a confirmed event from its current details, reviews previous → new with both warnings, and nothing changes yet', async ({ browser, request }) => {
  test.setTimeout(180_000)
  const eventName = uniqueName('EO03 Confirmed Gala')
  const venueId = await createVenue(request, uniqueName('EO03 Hall'))
  const eventId = await confirmedEventByApi(request, eventName, venueId, '2027-10-05T09:00:00+08:00', '2027-10-05T12:00:00+08:00')
  const organiser = await signedIn(browser, 'eo1')

  await test.step('AC2, AC8: the confirmed event offers a change, starting from its current details', async () => {
    await organiser.goto(`/organiser/events/${eventId}`)
    await expect(organiser.getByRole('link', { name: 'Request a change' })).toBeVisible()
    await evidence(organiser, 'EO03-TC1a')
    await organiser.getByRole('link', { name: 'Request a change' }).click()
    await expect(organiser.getByLabel('Event name')).toHaveValue(eventName)
    await expect(organiser.getByLabel('Expected attendance')).toHaveValue('120')
    await evidence(organiser, 'EO03-TC1b')
  })

  await test.step('AC22, AC23, AC24: the review shows only what changed, previous → new, and both warnings', async () => {
    await organiser.getByLabel('Expected attendance').fill('140')
    await organiser.getByLabel('End date & time').fill('2027-10-05T13:00')
    await organiser.getByRole('button', { name: 'Review the change' }).click()
    await expect(organiser.getByRole('row', { name: /Expected attendance/ })).toContainText('120')
    await expect(organiser.getByRole('row', { name: /Expected attendance/ })).toContainText('140')
    await expect(organiser.getByRole('row', { name: /Event name/ })).toHaveCount(0)
    await expect(organiser.getByText(/may mean your coordinator has to rearrange/)).toBeVisible()
    await expect(organiser.getByText(/your event goes back to Planning/)).toBeVisible()
    await organiser.getByLabel('Why is this change needed?').fill('Two more departments are joining')
    await evidence(organiser, 'EO03-TC1c')
  })

  await test.step('AC25: it is saved as waiting for a decision, with who asked, why and when', async () => {
    await organiser.getByRole('button', { name: 'Send change request' }).click()
    await expect(organiser.getByText(/Change request sent/)).toBeVisible()
    await expect(organiser.getByText('Waiting for a decision')).toBeVisible()
    await expect(organiser.getByText(/Asked by eo1/)).toBeVisible()
    await expect(organiser.getByText('Two more departments are joining')).toBeVisible()
    await evidence(organiser, 'EO03-TC1d')
  })

  await test.step('AC28, AC29, AC30: the event keeps its current details and status, and shows "Change requested"', async () => {
    await organiser.goto(`/organiser/events/${eventId}`)
    await expect(organiser.getByRole('region', { name: 'Change requested' })).toBeVisible()
    await evidence(organiser, 'EO03-TC1e')
    const event = await eventAs(request, eventId)
    expect(event.expectedAttendance).toBe(120)
    expect(event.journeyStatus.code).toBe('CONFIRMED')
  })
})

test('EO03 AC13, AC14, AC15, AC18, AC20 (negative): each proposed problem is shown next to its field, and a change that changes nothing cannot be reviewed', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventId = await planningEventByApi(request, uniqueName('EO03 Rules Gala'), '2027-10-06T09:00:00+08:00', '2027-10-06T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${eventId}/change`)
  const review = organiser.getByRole('button', { name: 'Review the change' })

  await test.step('AC20: with nothing changed, Review is unavailable and the organiser is told why', async () => {
    await expect(organiser.getByText('Change at least one detail to continue.')).toBeVisible()
    await expect(review).toBeDisabled()
    await evidence(organiser, 'EO03-TC2a')
  })

  await test.step('AC14, AC15, AC18: an end before the start, a past start and 1.5 people are each explained', async () => {
    await organiser.getByLabel('Start date & time').fill('2025-01-10T09:00')
    await organiser.getByLabel('End date & time').fill('2025-01-10T08:00')
    await expect(organiser.getByText('The start date & time must be in the future.')).toBeVisible()
    await expect(organiser.getByText('The end date & time must be after the start date & time.')).toBeVisible()
    await expect(review).toBeDisabled()
    // A whole-number field: the page does not accept a decimal point at all.
    await organiser.getByLabel('Expected attendance').pressSequentially('.5')
    await expect(organiser.getByLabel('Expected attendance')).not.toHaveValue(/\./)
    await evidence(organiser, 'EO03-TC2b')
    await organiser.getByLabel('Expected attendance').fill('0')
    await expect(organiser.getByText('Expected attendance must be at least 1.')).toBeVisible()
  })

  await test.step('AC13, AC20: the server refuses the same, and an unchanged request, even when the page is bypassed', async () => {
    const headers = { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }
    const current = await apiGet<Record<string, unknown>>(request, headers.Authorization.slice(7), `/api/event-requests/${requestId}`)
    const unchanged = await request.post('/api/change-requests', { headers, data: { requestId, reason: 'Nothing', details: current } })
    expect(unchanged.ok()).toBeFalsy()
    expect(await unchanged.text()).toContain('Nothing was changed')
    const backwards = await request.post('/api/change-requests', { headers, data: { requestId, reason: 'Backwards',
      details: { ...current, endDatetime: '2027-10-06T08:00:00+08:00' } } })
    expect(backwards.ok()).toBeFalsy()
    const decimal = await request.post('/api/change-requests', { headers, data: { requestId, reason: 'Decimal',
      details: { ...current, expectedAttendance: 150.5 } } })
    expect(decimal.ok()).toBeFalsy()
    expect(await changesOf(request, requestId)).toHaveLength(0)
  })
})

test('EO03 AC11, AC12 (boundary): a reason is required, 2,000 characters is accepted and 2,001 is refused', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventId = await planningEventByApi(request, uniqueName('EO03 Reason Gala'), '2027-10-07T09:00:00+08:00', '2027-10-07T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto(`/organiser/events/${eventId}/change`)
  await organiser.getByLabel('Purpose').fill('Annual planning offsite')
  await organiser.getByRole('button', { name: 'Review the change' }).click()
  const reason = organiser.getByLabel('Why is this change needed?')
  const send = organiser.getByRole('button', { name: 'Send change request' })

  await test.step('AC11: blank and spaces-only reasons cannot be sent', async () => {
    await expect(send).toBeDisabled()
    await reason.fill('     ')
    await expect(send).toBeDisabled()
  })

  await test.step('AC12: 2,001 characters is refused on the page and by the server', async () => {
    await reason.fill('x'.repeat(2001))
    await expect(organiser.getByText('Keep it to 2000 characters.')).toBeVisible()
    await expect(send).toBeDisabled()
    await evidence(organiser, 'EO03-TC3a')
    const refused = await request.post('/api/change-requests', {
      headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` },
      data: { requestId, reason: 'x'.repeat(2001), details: { purpose: 'Annual planning offsite' } } })
    expect(refused.ok()).toBeFalsy()
    expect(await changesOf(request, requestId)).toHaveLength(0)
  })

  await test.step('AC12: exactly 2,000 characters (after trimming spaces) is sent', async () => {
    await reason.fill(`  ${'y'.repeat(2000)}  `)
    await expect(organiser.getByText('2000 / 2000 characters. Your coordinator sees this.')).toBeVisible()
    await expect(send).toBeEnabled()
    await evidence(organiser, 'EO03-TC3b')
    await send.click()
    await expect(organiser.getByText(/Change request sent/)).toBeVisible()
    expect(await changesOf(request, requestId)).toHaveLength(1)
  })
})

test('EO03 AC26, AC27 (conflict): only one change request can wait at a time; the organiser is pointed to the one already waiting', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventId = await planningEventByApi(request, uniqueName('EO03 One Gala'), '2027-10-08T09:00:00+08:00', '2027-10-08T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  await changeRequestByApi(request, requestId, { expectedAttendance: 170 })
  const organiser = await signedIn(browser, 'eo1')

  await organiser.goto(`/organiser/events/${eventId}/change`)
  await expect(organiser.getByText(/You already asked to change this event on .* waiting for the coordinator's decision/)).toBeVisible()
  await expect(organiser.getByRole('link', { name: 'View your change request' })).toBeVisible()
  await evidence(organiser, 'EO03-TC4')

  const second = await request.post('/api/change-requests', {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` },
    data: { requestId, reason: 'Another', details: { expectedAttendance: 180 } } })
  expect(second.ok()).toBeFalsy()
  expect(await changesOf(request, requestId)).toHaveLength(1)
})

test('EO03 AC34, AC35, AC36 (happy): the organiser withdraws a waiting change; the event stays as it is and the coordinator is told (EC-NEW4 AC7)', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('EO03 Withdraw Gala')
  const eventId = await planningEventByApi(request, eventName, '2027-10-09T09:00:00+08:00', '2027-10-09T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const changeId = await changeRequestByApi(request, requestId, { expectedAttendance: 175 }, 'Expecting more guests')
  const coordinator = await signedIn(browser, 'ec1')

  await test.step('EC-NEW4 AC7, AC17: the coordinator is told a change was asked for, by whom and why', async () => {
    const item = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: `Change requested for "${eventName}"` })
    await expect(item).toBeVisible()
    await evidence(coordinator, 'EC-NEW4-TC2a')
  })

  const organiser = await signedIn(browser, 'eo1')
  await test.step('AC34: the organiser withdraws it after confirming', async () => {
    await organiser.goto(`/organiser/requests/${requestId}/changes`)
    await organiser.getByRole('button', { name: 'Withdraw…' }).click()
    await expect(organiser.getByText('Withdraw this change request? Your event stays as it is.')).toBeVisible()
    await organiser.getByRole('group', { name: 'Confirm withdrawal' }).getByRole('button', { name: 'Withdraw', exact: true }).click()
    await expect(organiser.getByText('Withdrawn', { exact: true }).first()).toBeVisible()
    await evidence(organiser, 'EO03-TC5')
    expect((await eventAs(request, eventId)).expectedAttendance).toBe(150)
  })

  await test.step('AC35: a request that is no longer waiting cannot be withdrawn again', async () => {
    const again = await request.post(`/api/change-requests/${changeId}/withdraw`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'eo1')}` } })
    expect(again.ok()).toBeFalsy()
  })

  await test.step('EC-NEW4 AC7: the coordinator is told it was withdrawn', async () => {
    await coordinator.reload()
    const item = (await openBell(coordinator)).getByRole('listitem').filter({ hasText: `Change request withdrawn for "${eventName}"` })
    await expect(item).toBeVisible()
    await evidence(coordinator, 'EC-NEW4-TC2b')
  })
})

test('EO03 AC1 (negative): another organisation cannot ask to change the event, in the page or by direct API call, and nothing is saved', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventId = await planningEventByApi(request, uniqueName('EO03 Private Gala'), '2027-10-10T09:00:00+08:00', '2027-10-10T12:00:00+08:00')
  const requestId = await requestOfEvent(request, eventId)
  const outsider = await signedIn(browser, 'eo3')

  await outsider.goto(`/organiser/requests/${requestId}/change`)
  await expect(outsider.getByRole('alert')).toHaveText(
    /You can't request a change to this event\. It belongs to another organisation, or it no longer exists\./)
  await expect(outsider.getByRole('button', { name: 'Try again' })).toHaveCount(0)
  await expect(outsider.getByLabel('Event name')).toHaveCount(0)
  await evidence(outsider, 'EO03-TC6')

  const refused = await request.post('/api/change-requests', {
    headers: { Authorization: `Bearer ${await apiToken(request, 'eo3')}` },
    data: { requestId, reason: 'Not mine', details: { expectedAttendance: 10 } } })
  expect(refused.ok()).toBeFalsy()
  expect(await changesOf(request, requestId)).toHaveLength(0)
})

test('EO03 AC3 (negative): a draft and a request awaiting clarification are not offered a change request', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const draftName = uniqueName('EO03 Draft Gala')
  const clarifyName = uniqueName('EO03 Clarify Gala')
  const eo = { Authorization: `Bearer ${await apiToken(request, 'eo1')}` }
  const draft = await request.post('/api/event-requests', { headers: eo, data: { eventName: draftName, purpose: 'Draft' } })
  expect(draft.ok(), await draft.text()).toBeTruthy()
  const clarifyId = await submittedRequestByApi(request, clarifyName, '2027-10-12T09:00:00+08:00', '2027-10-12T12:00:00+08:00')
  await assignedByApi(request, clarifyId)
  const asked = await request.post(`/api/event-requests/${clarifyId}/clarifications`, {
    headers: { Authorization: `Bearer ${await apiToken(request, 'ec1')}` },
    data: { message: 'Please confirm the numbers', flaggedFields: ['expectedAttendance'], fieldQuestions: {} } })
  expect(asked.ok(), await asked.text()).toBeTruthy()

  const organiser = await signedIn(browser, 'eo1')
  await organiser.goto('/organiser')
  for (const name of [draftName, clarifyName]) {
    const row = organiser.getByRole('listitem').filter({ has: organiser.getByRole('heading', { name }) })
    await expect(row).toBeVisible()
    await expect(row.getByRole('link', { name: 'Request a change' })).toHaveCount(0)
  }
  await evidence(organiser, 'EO03-TC7')
})
