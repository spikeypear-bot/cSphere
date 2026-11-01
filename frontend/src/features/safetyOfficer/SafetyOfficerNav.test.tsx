// @stories SO-C1  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
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

describe('SO-C1 safety officer console navigation', () => {
  beforeEach(() => {
    seedSession('safety-officer')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => jsonResponse(200, emptyBody(String(input)))))
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('SO-C1 (happy): the side navigation lists the safety officer pages in order', async () => {
    renderAt('/safety-officer')
    const nav = await screen.findByRole('navigation', { name: 'Safety Officer' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/safety-officer'],
      ['Safety reviews', '/safety-officer/reviews'],
    ])
  })

  it('SO-C1 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/safety-officer/reviews')
    const nav = await screen.findByRole('navigation', { name: 'Safety Officer' })
    expect(within(nav).getByRole('link', { name: 'Safety reviews' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
    expect(await screen.findByRole('heading', { name: 'Waiting for your safety review' })).toBeInTheDocument()
  })

  it('SO-C1 (negative): an unknown path under the safety officer console lands on the dashboard', async () => {
    renderAt('/safety-officer/no-such-page')
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, so1$/)
  })
})
