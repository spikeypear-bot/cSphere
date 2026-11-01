// @stories ECL-C1  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
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

describe('ECL-C1 Event Coordinator Lead console navigation', () => {
  beforeEach(() => {
    seedSession('coordinator-lead')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => jsonResponse(200, emptyBody(String(input)))))
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('ECL-C1 (happy): the side navigation lists the Lead pages in order', async () => {
    renderAt('/coordinator-lead')
    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator Lead' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/coordinator-lead'],
      ['Unassigned requests', '/coordinator-lead/unassigned-requests'],
      ['Assignments', '/coordinator-lead/assignments'],
      ['Reassignment requests', '/coordinator-lead/reassignment-requests'],
      ['Active events', '/coordinator-lead/active-events'],
    ])
  })

  it('ECL-C1 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/coordinator-lead/assignments')
    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator Lead' })
    expect(within(nav).getByRole('link', { name: 'Assignments' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
  })

  it('ECL-C1 (negative): an unknown path under the Lead console lands on the dashboard', async () => {
    renderAt('/coordinator-lead/no-such-page')
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ecl1$/)
  })
})
