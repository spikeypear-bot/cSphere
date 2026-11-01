// @stories ECL-C1 ECL-C6 ECL-C5 ECL-C7 EC01 EO26 EO19 EC-NEW4
import { expect, test } from '@playwright/test'
import { apiToken, assignedByApi, openBell, signedIn, submittedRequestByApi, uniqueName } from './support.ts'

// A request's path from arriving to being reviewed: the Lead finds and assigns
// it, the coordinator asks for clarification, the organiser answers, and the
// Lead can move it to another coordinator. Expected wording comes from the
// ECL-C1, ECL-C6, ECL-C5, EC01, EO26 and EO19 ACs (Sprint Backlogs 1–3).

const START = '2027-09-14T09:00:00+08:00'
const END = '2027-09-14T12:00:00+08:00'

test('ECL-C1 ECL-C6 EC01 EO26 EO19 (happy): a request is found, assigned, questioned and answered, and each person is told', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('E2E Clarify Gala')
  const requestId = await submittedRequestByApi(request, eventName, START, END, 150)

  const lead = await signedIn(browser, 'ecl1')
  await test.step('ECL-C1 AC2, AC4: the Lead sees the waiting request with its organisation and attendance', async () => {
    await lead.goto('/coordinator-lead/unassigned-requests')
    const row = lead.getByRole('listitem').filter({ hasText: eventName })
    await expect(row).toContainText('Acme Pte Ltd')
    await expect(row).toContainText('150')
  })

  await test.step('ECL-C6 AC3 (negative): assigning without a coordinator is blocked and nothing changes', async () => {
    await lead.goto(`/coordinator-lead/unassigned-requests/${requestId}`)
    await lead.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Assign' }).click()
    await lead.getByRole('button', { name: 'Assign request' }).click()
    await expect(lead.getByText('Choose an Event Coordinator before assigning.')).toBeVisible()
    await expect(lead).toHaveURL(new RegExp(`/unassigned-requests/${requestId}$`))
  })

  await test.step('ECL-C6 AC7, AC12 / ECL-C1 AC7: once assigned it leaves the list, with a confirmation naming the coordinator', async () => {
    await lead.getByLabel('Event Coordinator').selectOption({ label: 'ec1' })
    await lead.getByRole('button', { name: 'Assign request' }).click()
    await expect(lead.getByText(`“${eventName}” was assigned to ec1. The organiser has been notified.`)).toBeVisible()
    await expect(lead.getByRole('listitem').filter({ hasText: eventName })).toHaveCount(0)
  })

  const organiser = await signedIn(browser, 'eo1')
  await test.step('EO19 AC1, AC3, AC4: the organiser is told which coordinator now has their request', async () => {
    const panel = await openBell(organiser)
    await expect(panel.getByText(`"${eventName}" assigned to ec1`)).toBeVisible()
    await organiser.keyboard.press('Escape')
  })

  const coordinator = await signedIn(browser, 'ec1')
  await test.step('EC01 AC4, AC5: the coordinator flags the attendance, asks about it, and is told the organisers were notified', async () => {
    await coordinator.goto(`/coordinator/requests/${requestId}`)
    await coordinator.getByRole('button', { name: 'Flag Expected attendance' }).click()
    await coordinator.getByRole('button', { name: 'Request clarification (1)' }).click()
    const send = coordinator.getByRole('button', { name: 'Send clarification request' })
    await expect(send).toBeDisabled() // EC01 AC3: an unanswered question cannot be sent
    await coordinator.getByLabel(/What do you need to know more from the organiser about the Expected attendance/)
      .fill('Is 150 the final headcount, including staff?')
    await send.click()
    await expect(coordinator.getByText("Clarification sent. Acme Pte Ltd's organisers have been notified.")).toBeVisible()
  })

  await test.step('EC01 AC9: while waiting for the organiser, the request cannot be approved', async () => {
    await coordinator.reload()
    await expect(coordinator.getByText('Waiting for the organiser').first()).toBeVisible()
    await expect(coordinator.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' })).toHaveCount(0)
  })

  await test.step('EC01 AC8 / EO26 AC1, AC3: the organiser is told, and the flagged field is marked in their form', async () => {
    await organiser.reload()
    const panel = await openBell(organiser)
    await expect(panel.getByText(`Clarification needed on "${eventName}"`)).toBeVisible()
    await organiser.keyboard.press('Escape')
    await organiser.goto('/organiser/requests')
    await organiser.getByRole('listitem').filter({ hasText: eventName }).getByRole('link', { name: 'Respond' }).click()
    await expect(organiser).toHaveURL(new RegExp(`/organiser/requests/${requestId}/respond$`))
    await expect(organiser.getByText('Requested by your coordinator')).toBeVisible()
    await expect(organiser.getByText('Is 150 the final headcount, including staff?').first()).toBeVisible()
  })

  await test.step('EO26 AC6 (negative): resubmitting needs a reply', async () => {
    await organiser.getByLabel('Expected attendance').fill('120')
    await expect(organiser.getByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
    await organiser.getByLabel('Your reply to the coordinator').fill('   ')
    await expect(organiser.getByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
  })

  await test.step('EO26 AC8: with a reply, it goes back for review', async () => {
    await organiser.getByLabel('Your reply to the coordinator').fill('120 confirmed, staff included.')
    await organiser.getByRole('button', { name: 'Save and resubmit' }).click()
    await expect(organiser.getByText('Your updated request has been sent back for review.')).toBeVisible()
  })

  await test.step('EC01 AC10, AC11, AC12: the same coordinator is told, and sees what changed and the whole conversation', async () => {
    await coordinator.reload()
    const panel = await openBell(coordinator)
    await expect(panel.getByText(`"${eventName}" was updated and resubmitted`)).toBeVisible()
    await coordinator.keyboard.press('Escape')
    await coordinator.goto(`/coordinator/requests/${requestId}`)
    const changes = coordinator.getByRole('region', { name: 'What changed since you asked' })
      .or(coordinator.getByLabel('What changed since you asked'))
    await expect(changes).toContainText('150')
    await expect(changes).toContainText('120')
    await expect(coordinator.getByText('120 confirmed, staff included.').first()).toBeVisible()
    await expect(coordinator.getByText('Is 150 the final headcount, including staff?').first()).toBeVisible()
  })
})

test('ECL-C5 EO19 (happy): the Lead moves a request to another coordinator; the previous one loses it and the organiser is told', async ({ browser, request }) => {
  test.setTimeout(120_000)
  const eventName = uniqueName('E2E Reassign Gala')
  const requestId = await submittedRequestByApi(request, eventName, START, END)
  await assignedByApi(request, requestId, 'ec1')

  const lead = await signedIn(browser, 'ecl1')
  await test.step('ECL-C5 AC2, AC3 (negative): from Coordinator assignments, reassigning without a choice is blocked', async () => {
    await lead.goto('/coordinator-lead/assignments')
    await lead.getByRole('link', { name: `View request: ${eventName}` }).click()
    await lead.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Reassign' }).click()
    await lead.getByRole('button', { name: 'Reassign request' }).click()
    await expect(lead.getByText('Choose an Event Coordinator before reassigning.')).toBeVisible()
  })

  await test.step('ECL-C5 AC13: reassigned to ec2, with a confirmation naming the request and coordinator', async () => {
    await lead.getByLabel('Event Coordinator').selectOption({ label: 'ec2' })
    await lead.getByRole('button', { name: 'Reassign request' }).click()
    await expect(lead.getByText(`“${eventName}” was reassigned to ec2. The organiser has been notified.`)).toBeVisible()
  })

  await test.step('ECL-C5 AC9 / ECL-C7: the previous coordinator gets the "not assigned to you" page instead of the request', async () => {
    const previous = await signedIn(browser, 'ec1')
    await previous.goto(`/coordinator/requests/${requestId}`)
    await expect(previous.getByRole('heading', { level: 1, name: "This request isn't assigned to you" })).toBeVisible()
    await expect(previous.getByText(eventName)).toHaveCount(0)
    await expect(previous.getByRole('button', { name: 'Send clarification request' })).toHaveCount(0)
  })

  await test.step('ECL-C5 AC8: it is in the new coordinator\'s review queue', async () => {
    const next = await signedIn(browser, 'ec2')
    await next.goto('/coordinator/review-queue')
    await expect(next.getByText(eventName).first()).toBeVisible()
  })

  await test.step('EO19 AC7, AC8, AC9, AC10: the organiser sees a reassignment naming only the new coordinator', async () => {
    const organiser = await signedIn(browser, 'eo1')
    const panel = await openBell(organiser)
    await expect(panel.getByText(`"${eventName}" reassigned to ec2`)).toBeVisible()
    await expect(panel.getByRole('listitem').filter({ hasText: `"${eventName}" reassigned to` })).not.toContainText('ec1')
  })

  await test.step('ECL-C5 AC1 (negative): a coordinator cannot reassign by direct API call', async () => {
    const people = await (await request.get('/api/event-requests/unassigned/coordinators', {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ecl1')}` } })).json() as { userId: string; username: string }[]
    const refused = await request.post(`/api/event-requests/assigned/${requestId}/reassign`, {
      headers: { Authorization: `Bearer ${await apiToken(request, 'ec2')}` },
      data: { coordinatorUserId: people.find((p) => p.username === 'ec1')!.userId } })
    expect(refused.status()).toBe(403)
  })
})
