// @stories AU06
import { expect, test } from '@playwright/test'
import { signedIn } from './support.ts'

// AU06: a person who opens a page their role cannot use is moved to their own
// console and told why, naming the page; a signed-out visitor is asked to
// sign in, not told they were denied. Expected wording comes from the AU06 ACs.

const ATTEMPTS: { username: string; home: RegExp; path: string }[] = [
  { username: 'eo1', home: /\/organiser/, path: '/venue-staff/booking-approvals' },
  { username: 'ec1', home: /\/coordinator/, path: '/coordinator-lead/unassigned-requests' },
  { username: 'vs1', home: /\/venue-staff/, path: '/safety-officer' },
  { username: 'att1', home: /\/attendee/, path: '/technical-support/requests' },
]

for (const { username, home, path } of ATTEMPTS) {
  test(`AU06 AC1, AC2, AC3 (negative): ${username} opening ${path} is moved to their own console and told which page was denied`, async ({ browser }) => {
    const page = await signedIn(browser, username)
    await page.goto(path)
    await expect(page).toHaveURL(home)
    const denied = page.getByRole('alert').filter({ has: page.getByRole('heading', { name: 'Access denied' }) })
    await expect(denied).toContainText(`You do not have permission to access ${path} with your current role.`)
  })
}

test('AU06 AC4 (negative): a signed-out visitor is asked to sign in for that page, without an access-denied message', async ({ page }) => {
  await page.goto('/coordinator/review-queue')
  await expect(page.getByLabel('Username')).toBeVisible()
  await expect(page.getByText('Please sign in to open /coordinator/review-queue.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Access denied' })).toHaveCount(0)
})

test('AU06 (cross-cutting): the API refuses the same person too, so the message is not just the screen hiding a link', async ({ request }) => {
  const login = await request.post('/api/auth/login', { data: { username: 'eo1', password: '123456' } })
  const token = (await login.json()).accessToken as string
  const refused = await request.get('/api/venue-staff/booking-requests', { headers: { Authorization: `Bearer ${token}` } })
  expect(refused.status()).toBe(403)
  expect(await refused.text()).not.toContain('bookingId')
})
