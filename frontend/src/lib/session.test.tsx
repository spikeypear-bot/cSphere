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

  it("maps the backend's role abbreviation onto the console vocabulary", async () => {
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

  it('logs the user out when apiClient reports a session that could not be renewed', async () => {
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

  it('resumes a stored session on reload', () => {
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

  it('treats an unrecognised role as not logged in rather than guessing a console', () => {
    window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'a' }))
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    )
    expect(screen.getByText('role: none')).toBeInTheDocument()
  })
})
