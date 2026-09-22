import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { LoginPage } from './LoginPage'

function renderLogin(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <LoginPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('posts the credentials to /api/auth/login with no Authorization header', async () => {
    const fetchSpy = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () =>
        JSON.stringify({
          accessToken: 'access-1',
          refreshToken: 'refresh-1',
          tokenType: 'Bearer',
          expiresIn: 900,
          username: 'vs1',
          role: 'vs',
          organisation: 'ConnectSphere',
        }),
    } as Response)
    vi.stubGlobal('fetch', fetchSpy)
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('Username'), 'vs1')
    await user.type(screen.getByLabelText('Password'), '123456')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'vs1', password: '123456' }),
      }),
    )
    const headers = fetchSpy.mock.calls[0][1].headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })

  it("shows the backend's message on a rejected login and clears the password", async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        text: async () => JSON.stringify({ message: 'Invalid username or password' }),
      } as Response),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('Username'), 'vs1')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('names the page the visitor was trying to reach', () => {
    renderLogin('/?access-denied=%2Fvenue-staff')
    expect(screen.getByRole('status')).toHaveTextContent('/venue-staff')
  })

  it('will not submit until both fields are filled', async () => {
    const user = userEvent.setup()
    renderLogin()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
    await user.type(screen.getByLabelText('Username'), 'vs1')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
    await user.type(screen.getByLabelText('Password'), '123456')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })
})
