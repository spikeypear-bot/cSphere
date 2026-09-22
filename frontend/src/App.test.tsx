import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { AUTH_STORAGE_KEY, type Role } from './lib/authTokens'

/** Seeds a signed-in session the way a successful login would. The app reads
 * role from stored token data, so tests no longer click a role selector. */
function signInAs(role: Role, username = 'user1', organisation: string | null = null) {
  window.localStorage.setItem(
    AUTH_STORAGE_KEY,
    JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', username, role, organisation }),
  )
}

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App routing — role consoles', () => {
  afterEach(() => {
    cleanup()
    window.localStorage.clear()
  })

  it('sends an unauthenticated visit to a role route to the login page', () => {
    window.localStorage.clear()
    renderApp('/coordinator')
    expect(screen.getByRole('heading', { name: /Sign in to ConnectSphere/i })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('/coordinator')
  })

  it('opens the console belonging to the signed-in account', async () => {
    signInAs('coordinator', 'ec1', 'ConnectSphere')
    renderApp('/coordinator')

    expect(await screen.findByRole('heading', { name: /Event Coordinator console/i })).toBeInTheDocument()
    // Every skeleton card names its backlog story ID so a teammate can trace it.
    expect(screen.getByText('EC01')).toBeInTheDocument()
  })

  it('sends a signed-in visitor at "/" straight to their own console — there is no role to pick', async () => {
    signInAs('attendee', 'att1', 'Acme Pte Ltd')
    renderApp('/')
    expect(await screen.findByRole('heading', { name: /Attendee console/i })).toBeInTheDocument()
  })

  it('shows the vertical-slice checklist and story ID on a skeleton feature page', async () => {
    const user = userEvent.setup()
    signInAs('technical-support', 'tech1', 'ConnectSphere')
    renderApp('/technical-support')

    await user.click(screen.getByRole('link', { name: /Check availability/i }))

    expect(await screen.findByRole('heading', { name: 'Equipment Availability' })).toBeInTheDocument()
    expect(screen.getByText('TS01')).toBeInTheDocument()
    expect(screen.getByText(/Build this as one "strand of hair"/i)).toBeInTheDocument()
    expect(screen.getByText('com.example.connect_sphere.equipment')).toBeInTheDocument()
  })

  it("redirects a signed-in account away from another role's console to its own", async () => {
    signInAs('attendee', 'att1')
    renderApp('/venue-staff')

    // Their own console, not the login page: the session is valid, this just
    // isn't their area. The server rejects the API calls regardless (D20).
    expect(await screen.findByRole('heading', { name: /Attendee console/i })).toBeInTheDocument()
  })

  it('identifies the signed-in account in the shell', async () => {
    signInAs('organiser', 'eo1', 'Acme Pte Ltd')
    renderApp('/organiser')

    expect(await screen.findByText(/eo1 · Event Organiser · Acme Pte Ltd/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument()
  })
})
