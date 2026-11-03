// @stories AU04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../lib/session'
import { emptyBody, jsonResponse, seedSession } from '../test/apiStubs'
import { AppShell } from './AppShell'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('AU04 the header Log out button', () => {
  it('AU04 (happy): logging out removes the signed-in identity and ends the session on the server', async () => {
    seedSession('venue-staff')
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/logout') return { ok: true, status: 204, text: async () => '' } as Response
      return jsonResponse(200, url.endsWith('/unread-count') ? { count: 0 } : emptyBody(url))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<MemoryRouter><SessionProvider><AppShell><p>Page</p></AppShell></SessionProvider></MemoryRouter>)

    expect(screen.getByText(/vs1 · Venue Staff/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Log out' }))

    expect(screen.queryByRole('button', { name: 'Log out' })).not.toBeInTheDocument()
    expect(screen.queryByText(/vs1 · Venue Staff/)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }))
    expect(window.localStorage.getItem('connectsphere.auth')).toBeNull()
  })
})
