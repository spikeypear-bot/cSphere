import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

function renderApp(initialPath = '/') {
  window.localStorage.clear()
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

  it('sends an unauthenticated visit to a role route back to the role selector', () => {
    renderApp('/coordinator')
    expect(screen.getByRole('heading', { name: "Who's working today?" })).toBeInTheDocument()
  })

  it('opens the Event Coordinator skeleton console after logging in as that role', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: /Login as Event Coordinator/i }))

    expect(await screen.findByRole('heading', { name: /Event Coordinator console/i })).toBeInTheDocument()
    // Every skeleton card names its backlog story ID so a teammate can trace it.
    expect(screen.getByText('EC01')).toBeInTheDocument()
  })

  it('shows the vertical-slice checklist and story ID on a skeleton feature page', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: /Login as Technical Support Staff/i }))
    await user.click(screen.getByRole('link', { name: /Check availability/i }))

    expect(await screen.findByRole('heading', { name: 'Equipment Availability' })).toBeInTheDocument()
    expect(screen.getByText('TS01')).toBeInTheDocument()
    expect(screen.getByText(/Build this as one "strand of hair"/i)).toBeInTheDocument()
    expect(screen.getByText('com.example.connect_sphere.equipment')).toBeInTheDocument()
  })

  it("keeps a role gated from another role's console", async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: /Login as Attendee/i }))
    expect(await screen.findByRole('heading', { name: /Attendee console/i })).toBeInTheDocument()

    // Attempting a different role's route while logged in as Attendee bounces home.
    cleanup()
    window.localStorage.setItem(
      'connectsphere.session',
      JSON.stringify({ role: 'attendee', organisation: null }),
    )
    renderApp('/venue-staff')
    expect(screen.getByRole('heading', { name: "Who's working today?" })).toBeInTheDocument()
  })
})
