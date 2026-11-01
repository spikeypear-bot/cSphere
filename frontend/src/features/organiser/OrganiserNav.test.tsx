// @stories EO15  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from '../../App'
import { emptyBody, jsonResponse, seedSession } from '../../test/apiStubs'

/** Every organiser read answers "nothing here", so any page renders. */
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

describe('EO15 organiser console navigation', () => {
  beforeEach(() => {
    seedSession('organiser')
    stubEmptyReads()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EO15 (happy): the side navigation lists the organiser pages in order', async () => {
    renderAt('/organiser')
    const nav = await screen.findByRole('navigation', { name: 'Event Organiser' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/organiser'],
      ['My requests', '/organiser/requests'],
      ['New request', '/organiser/requests/new'],
    ])
  })

  it('EO15 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/organiser/requests')
    const nav = await screen.findByRole('navigation', { name: 'Event Organiser' })
    expect(within(nav).getByRole('link', { name: 'My requests' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
    expect(await screen.findByRole('heading', { level: 1, name: 'Your event requests' })).toBeInTheDocument()
  })

  it.each(['/organiser/change-request', '/organiser/confirmed', '/organiser/no-such-page'])(
    'EO15 (negative): the unknown or retired path %s lands on the dashboard', async (path) => {
      renderAt(path)
      expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, eo1$/)
    })
})
