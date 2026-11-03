// @stories DEV05 AU04  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UNAUTHORISED_EVENT } from './apiClient'
import { AUTH_STORAGE_KEY } from './authTokens'
import { SessionProvider } from './session'
import { useSession } from './sessionContext'

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
  } as Response
}

const LOGIN_RESPONSE = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  username: 'eo1',
  role: 'eo',
  organisation: 'Acme Pte Ltd',
}

function Probe() {
  const { role, username, login } = useSession()
  return (
    <div>
      <span>role: {role ?? 'none'}</span>
      <span>user: {username ?? 'none'}</span>
      <button type="button" onClick={() => void login('eo1', '123456')}>
        log in
      </button>
    </div>
  )
}

describe('SessionProvider', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it("AU04 (happy): the backend's role abbreviation opens the matching console", async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(LOGIN_RESPONSE)))
    const user = userEvent.setup()
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'log in' }))

    // The token claims `eo`; the routes and UI say `organiser`.
    expect(await screen.findByText('role: organiser')).toBeInTheDocument()
    expect(screen.getByText('user: eo1')).toBeInTheDocument()
  })

  it('DEV05 (failure): the user is logged out when the session could not be renewed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(LOGIN_RESPONSE)))
    const user = userEvent.setup()
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'log in' }))
    expect(await screen.findByText('role: organiser')).toBeInTheDocument()

    act(() => {
      window.dispatchEvent(new CustomEvent(UNAUTHORISED_EVENT))
    })

    expect(screen.getByText('role: none')).toBeInTheDocument()
  })

  it('DEV05 AU04 (happy): a stored session resumes on reload', () => {
    window.localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        username: 'vs1',
        role: 'venue-staff',
        organisation: 'ConnectSphere',
      }),
    )
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )
    expect(screen.getByText('role: venue-staff')).toBeInTheDocument()
  })

  it('AU04 (negative): an unrecognised role is treated as not logged in rather than guessing a console', () => {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'a' }))
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )
    expect(screen.getByText('role: none')).toBeInTheDocument()
  })

  it('AU04 (happy): logging out signs the person out here and revokes the session on the server', async () => {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', username: 'vs1', role: 'venue-staff' }))
    const fetchSpy = vi.fn().mockResolvedValue({ ok: true, status: 204, text: async () => '' } as Response)
    vi.stubGlobal('fetch', fetchSpy)
    function LogoutProbe() {
      const { role, logout } = useSession()
      return <button type="button" onClick={() => void logout()}>log out ({role ?? 'none'})</button>
    }
    const user = userEvent.setup()
    render(<SessionProvider><LogoutProbe /></SessionProvider>)

    await user.click(screen.getByRole('button', { name: 'log out (venue-staff)' }))

    expect(await screen.findByRole('button', { name: 'log out (none)' })).toBeInTheDocument()
    expect(fetchSpy).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }))
    expect(window.localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull()
  })

  it('DEV05 (negative): using the session outside a SessionProvider is a programming error that says so', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Probe />)).toThrow('useSession must be used within a SessionProvider')
    vi.restoreAllMocks()
  })
})
