// @stories ECL-C7 ELC-C5 ECL-C2 EC09 EO01 EO02 EC02
import { expect, test } from '@playwright/test'
import { leadAssigns, requestIdOf, signedIn, submitRequest, uniqueName } from './support.ts'

// ECL-C7 through the browser: the Lead's assignment decides who may work on an
// event. When the Lead reassigns an event, the previous coordinator loses it
// at once (with a clear message, not an error) and the new one has it.

test('reassigning an event moves it from one coordinator to another at once (ECL-C7)', async ({ browser, request }) => {
  test.setTimeout(150_000)
  const eventName = uniqueName('E2E Handover Gala')
  const organiser = await signedIn(browser, 'eo1')
  await submitRequest(organiser, eventName, '2027-09-07T09:00', '2027-09-07T12:00')
  const requestId = await requestIdOf(request, eventName)
  const lead = await signedIn(browser, 'ecl1')
  await leadAssigns(lead, requestId)

  const previous = await signedIn(browser, 'ec1')
  await previous.goto(`/coordinator/requests/${requestId}`)
  await previous.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Approve' }).click()
  await previous.getByRole('button', { name: 'Approve and start planning' }).click()
  await expect(previous).toHaveURL(/\/coordinator\/events\/[0-9a-f-]{36}/)
  const eventUrl = previous.url()

  await test.step('ECL-C7 AC20: the Lead sees the event under ec1 and reassigns it to ec2', async () => {
    await lead.goto('/coordinator-lead/assignments')
    const held = lead.getByRole('list', { name: 'Events held by ec1' })
    await expect(held.getByText(eventName)).toBeVisible()
    await held.getByRole('link', { name: `Reassign: ${eventName}` }).click()
    await expect(lead.getByText(/the current coordinator loses access straight away/)).toBeVisible()
    await lead.getByRole('group', { name: 'Choose an outcome' }).getByRole('button', { name: 'Reassign' }).click()
    await lead.getByLabel('Event Coordinator').selectOption({ label: 'ec2' })
    await lead.getByRole('button', { name: 'Reassign request' }).click()
    await expect(lead.getByRole('list', { name: 'Events held by ec2' }).getByText(eventName)).toBeVisible()
  })

  await test.step('ECL-C7 AC14, AC18: the previous coordinator is told it is no longer theirs, and sees nothing of it', async () => {
    await previous.goto(eventUrl)
    await expect(previous.getByRole('heading', { name: "This event isn't assigned to you" })).toBeVisible()
    await expect(previous.getByText(eventName)).toHaveCount(0)
    await previous.goto('/coordinator/my-events')
    await expect(previous.getByRole('heading', { name: 'My events' })).toBeVisible()
    await expect(previous.getByText(eventName)).toHaveCount(0)
  })

  await test.step('ECL-C7 AC15, AC8: the new coordinator has it in My events and can open it', async () => {
    const next = await signedIn(browser, 'ec2')
    await next.goto('/coordinator/my-events')
    await next.getByRole('link', { name: `Open event ${eventName}` }).click()
    await expect(next.getByRole('heading', { name: eventName })).toBeVisible()
    await expect(next.getByRole('region', { name: 'Ready for confirmation?' })).toBeVisible()
  })
})
