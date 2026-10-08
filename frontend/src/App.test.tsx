import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { AUTH_STORAGE_KEY, type Role } from './lib/authTokens'
import { jsonResponse } from './test/apiStubs'

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
    vi.unstubAllGlobals()
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
    // EC01/EC02's review queue is a real page now, reached via its own
    // button rather than a "Skeleton" card (see coordinatorFeatures.ts).
    expect(screen.getByRole('link', { name: 'Review requests' })).toHaveAttribute(
      'href', '/coordinator/review-queue',
    )
    // Every *remaining* skeleton card still names its backlog story ID so a
    // teammate can trace it.
    expect(screen.getByText('EC04')).toBeInTheDocument()
  })

  // ---- EC09: "My events" belongs to the Event Coordinator console ----

  it('puts "My events" one click away on the Event Coordinator console and opens the page there', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    const user = userEvent.setup()
    signInAs('coordinator', 'ec1', 'ConnectSphere')
    renderApp('/coordinator')

    const link = await screen.findByRole('link', { name: 'My events' })
    expect(link).toHaveAttribute('href', '/coordinator/my-events')
    await user.click(link)

    expect(await screen.findByRole('heading', { level: 1, name: 'My events' })).toBeInTheDocument()
    expect(await screen.findByText(/No events are assigned to you right now\./)).toBeInTheDocument()
  })

  it('redirects an Event Coordinator Lead away from "My events" and says why', async () => {
    signInAs('coordinator-lead', 'ecl1', 'ConnectSphere')
    renderApp('/coordinator/my-events')

    // Redirected, so the page that fetches the list is never mounted.
    expect(await screen.findByRole('heading', { name: /Event Coordinator Lead console/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My events' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/coordinator/my-events')
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

    await user.click(screen.getByRole('link', { name: /Skeleton Check availability/i }))

    expect(await screen.findByRole('heading', { name: 'Equipment Availability' })).toBeInTheDocument()
    expect(screen.getByText('TS01')).toBeInTheDocument()
    expect(screen.getByText(/Build this as one "strand of hair"/i)).toBeInTheDocument()
    expect(screen.getByText('com.example.connect_sphere.equipment')).toBeInTheDocument()
  })

  it("redirects a signed-in account away from another role's console and says why", async () => {
    signInAs('attendee', 'att1')
    renderApp('/venue-staff')

    // Their own console, not the login page: the session is valid, this just
    // isn't their area. The server rejects the API calls regardless (D20).
    expect(await screen.findByRole('heading', { name: /Attendee console/i })).toBeInTheDocument()

    // AU06 — a silent redirect reads as the app misbehaving. Originally
    // delivered on the role-select screen (PR #11); it moved to AppShell when
    // that screen was replaced by a real login page.
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/venue-staff')
  })

  it('does not show the access-denied alert during ordinary navigation', async () => {
    signInAs('attendee', 'att1')
    renderApp('/attendee')

    expect(await screen.findByRole('heading', { name: /Attendee console/i })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('identifies the signed-in account in the shell', async () => {
    signInAs('organiser', 'eo1', 'Acme Pte Ltd')
    renderApp('/organiser')

    expect(await screen.findByText(/eo1 · Event Organiser · Acme Pte Ltd/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument()
  })

  // ---- ECL-C1: the Event Coordinator Lead is its own role and console ----

  it('lands a signed-in Event Coordinator Lead on their own console, with the unassigned queue one click away', async () => {
    signInAs('coordinator-lead', 'ecl1', 'ConnectSphere')
    renderApp('/')

    expect(await screen.findByRole('heading', { name: /Event Coordinator Lead console/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Unassigned requests' })).toHaveAttribute(
      'href', '/coordinator-lead/unassigned-requests',
    )
    expect(screen.getByText(/ecl1 · Event Coordinator Lead · ConnectSphere/)).toBeInTheDocument()
  })

  it("redirects an Event Coordinator away from the Lead's unassigned queue and says why", async () => {
    signInAs('coordinator', 'ec1', 'ConnectSphere')
    renderApp('/coordinator-lead/unassigned-requests')

    // Redirected, so the page that fetches the queue is never mounted.
    expect(await screen.findByRole('heading', { name: /Event Coordinator console/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Unassigned requests' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/coordinator-lead/unassigned-requests')
  })

  // ---- ECL-C2: coordinator assignments belong to the Lead's console ----

  it('puts "Coordinator assignments" one click away on the Lead console and opens the page there', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    const user = userEvent.setup()
    signInAs('coordinator-lead', 'ecl1', 'ConnectSphere')
    renderApp('/coordinator-lead')

    const link = await screen.findByRole('link', { name: 'Coordinator assignments' })
    expect(link).toHaveAttribute('href', '/coordinator-lead/assignments')
    await user.click(link)

    expect(await screen.findByRole('heading', { level: 1, name: 'Coordinator assignments' })).toBeInTheDocument()
    expect(await screen.findByText(/No assigned requests right now\./)).toBeInTheDocument()
  })

  it.each([
    ['coordinator', 'ec1', /Event Coordinator console/i],
    ['organiser', 'eo1', /Your event requests/i],
    ['venue-staff', 'vs1', /Venue Staff console/i],
    ['technical-support', 'ts1', /Technical Support Staff console/i],
    ['attendee', 'att1', /Attendee console/i],
  ] as const)('redirects a signed-in %s away from coordinator assignments, says why and reads no request data', async (role, username, ownConsole) => {
    const fetched: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      fetched.push(String(input))
      return jsonResponse(200, [])
    }))
    signInAs(role, username)
    renderApp('/coordinator-lead/assignments')

    expect(await screen.findByRole('heading', { name: ownConsole })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Coordinator assignments' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/coordinator-lead/assignments')
    // The page that reads the list is never mounted.
    expect(fetched).not.toContain('/api/event-requests/assigned')
  })

  it("redirects an Event Coordinator Lead away from the Event Coordinator's console — it is a separate role", async () => {
    signInAs('coordinator-lead', 'ecl1', 'ConnectSphere')
    renderApp('/coordinator/review-queue')

    expect(await screen.findByRole('heading', { name: /Event Coordinator Lead console/i })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/coordinator/review-queue')
  })
})
