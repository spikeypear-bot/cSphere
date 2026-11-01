import { expect, test, type Page } from '@playwright/test'

// IS212 Week 9: a few end-to-end tests for the journeys that matter most,
// through a real browser, the real backend and a real database. Locators are
// by role and label, as a user finds things; `expect` waits for a condition
// instead of sleeping. Accounts come from the dev profile's DevUserSeeder.

async function signIn(page: Page, username: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill('123456')
  await page.getByRole('button', { name: 'Sign in' }).click()
}

test('an organiser signs in and lands on their own event requests', async ({ page }) => {
  await signIn(page, 'eo1')

  await expect(page.getByRole('heading', { level: 1, name: /^Good \w+, eo1$/ })).toBeVisible()
  await page.getByRole('navigation', { name: 'Event Organiser' }).getByRole('link', { name: 'My requests' }).click()
  await expect(page.getByRole('heading', { name: 'Your event requests' })).toBeVisible()
  // Scoped to their own organisation (D20): the page names it.
  await expect(page.getByText('Acme Pte Ltd', { exact: true })).toBeVisible()
})

test('a wrong password is refused and the person stays on the sign-in page', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill('eo1')
  await page.getByLabel('Password').fill('not-the-password')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('alert')).toHaveText('Invalid username or password')
  await expect(page.getByRole('heading', { name: 'Sign in to ConnectSphere' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: /^Good \w+, eo1$/ })).toHaveCount(0)
})

test("an organiser's draft is saved to the server and listed with their requests (EO02/EO15)", async ({ page }) => {
  // Fresh, visible test data (Week 9): a unique name, so reruns never
  // depend on what an earlier run left behind.
  const eventName = `E2E Town Hall ${Date.now()}`
  await signIn(page, 'eo1')

  await page.getByRole('link', { name: 'New event request' }).click()
  await page.getByLabel('Event name').fill(eventName)
  await page.getByRole('button', { name: 'Exit' }).click()

  await expect(page.getByRole('heading', { name: 'Your event requests' })).toBeVisible()
  const draft = page.getByRole('listitem').filter({ hasText: eventName })
  await expect(draft).toBeVisible()
  await expect(draft.getByText(/not submitted yet/)).toBeVisible()

  // It really is on the server: a full reload still shows it.
  await page.reload()
  await expect(page.getByRole('listitem').filter({ hasText: eventName })).toBeVisible()
})
