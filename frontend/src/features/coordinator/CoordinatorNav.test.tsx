// @stories EC09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from '../../App'
import { emptyBody, jsonResponse, seedSession } from '../../test/apiStubs'

/** Every coordinator read answers "nothing here", so any page renders. */
function stubEmptyReads() {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => jsonResponse(200, emptyBody(String(input)))))
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('EC09 coordinator console navigation', () => {
  beforeEach(() => {
    seedSession('coordinator')
    stubEmptyReads()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC09 (happy): the side navigation lists the coordinator pages in order', async () => {
    renderAt('/coordinator')
    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/coordinator'],
      ['Review queue', '/coordinator/review-queue'],
      ['My events', '/coordinator/my-events'],
      ['Venue issues', '/coordinator/operational-issues'],
      ['Search venues', '/coordinator/venue-search'],
    ])
  })

  it('EC09 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/coordinator/my-events')
    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator' })
    expect(within(nav).getByRole('link', { name: 'My events' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
  })

  it.each(['/coordinator/confirm-event', '/coordinator/change-review'])(
    'EC09 (negative): the retired placeholder %s lands on the dashboard', async (path) => {
      renderAt(path)
      expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ec1$/)
    })
})
