// @stories AT04  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from '../../App'
import { AUTH_STORAGE_KEY } from '../../lib/authTokens'
import { emptyBody, jsonResponse } from '../../test/apiStubs'

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}

describe('AT04 attendee console navigation', () => {
  beforeEach(() => {
    window.localStorage.setItem(AUTH_STORAGE_KEY,
      JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', username: 'att1', role: 'attendee', organisation: null }))
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => jsonResponse(200, emptyBody(String(input)))))
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('AT04 (happy): the side navigation lists the attendee pages in order', async () => {
    renderAt('/attendee')
    const nav = await screen.findByRole('navigation', { name: 'Attendee' })
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/attendee'],
      ['Browse events', '/attendee/browse'],
      ['My registrations', '/attendee/my-registrations'],
    ])
  })

  it('AT04 (happy): the current page is highlighted in the navigation', async () => {
    renderAt('/attendee/my-registrations')
    const nav = await screen.findByRole('navigation', { name: 'Attendee' })
    expect(within(nav).getByRole('link', { name: 'My registrations' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
  })

  it('AT04 (negative): an unknown attendee path lands on the dashboard', async () => {
    renderAt('/attendee/no-such-page')
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, att1$/)
  })

  it('AT04 (boundary): the old register link still opens Browse events', async () => {
    renderAt('/attendee/register')
    expect(await screen.findByRole('heading', { level: 1, name: 'Browse events' })).toBeInTheDocument()
  })
})
