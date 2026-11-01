// @stories VS02  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from '../../App'
import { emptyBody, jsonResponse, seedSession } from '../../test/apiStubs'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('VS02 venue staff console navigation', () => {
  beforeEach(() => {
    seedSession('venue-staff')
    // Every venue staff read answers "nothing here", so any page renders.
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => jsonResponse(200, emptyBody(String(input)))))
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('VS02 (happy): the side navigation lists the venue staff pages in order', async () => {
    renderAt('/venue-staff')
    const nav = await screen.findByRole('navigation', { name: 'Venue Staff' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/venue-staff'],
      ['Booking approvals', '/venue-staff/booking-approvals'],
      ['Venue catalogue', '/venue-staff/catalogue'],
      ['Availability calendar', '/venue-staff/availability-calendar'],
    ])
  })

  it('VS02 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/venue-staff/catalogue')
    const nav = await screen.findByRole('navigation', { name: 'Venue Staff' })
    expect(within(nav).getByRole('link', { name: 'Venue catalogue' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
  })

  it('VS02 (happy): the catalogue still links to adding a venue', async () => {
    renderAt('/venue-staff/catalogue')
    expect(await screen.findByRole('link', { name: 'Add a venue' })).toHaveAttribute('href', '/venue-staff/catalogue/new')
  })

  it('VS02 (negative): an unknown venue staff path lands on the dashboard', async () => {
    renderAt('/venue-staff/no-such-page')
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, vs1$/)
  })
})
