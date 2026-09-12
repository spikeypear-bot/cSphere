import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { RoleSelectPage } from './RoleSelectPage'

function renderPage() {
  window.localStorage.clear()
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<RoleSelectPage />} />
          <Route path="/organiser" element={<div>Organiser home</div>} />
          <Route path="/venue-staff" element={<div>Venue Staff console</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('RoleSelectPage', () => {
  afterEach(() => {
    cleanup()
    window.localStorage.clear()
  })

  it('shows a card for every role named in the customer briefing', () => {
    renderPage()
    for (const title of [
      'Event Organiser',
      'Event Coordinator',
      'Venue Staff',
      'Technical Support Staff',
      'Attendee',
    ]) {
      expect(screen.getByRole('heading', { name: title })).toBeInTheDocument()
    }
  })

  it('asks for an organisation before entering the Event Organiser console', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /Login as Event Organiser/i }))

    expect(screen.getByRole('dialog', { name: /organisation/i })).toBeInTheDocument()
    expect(screen.queryByText('Organiser home')).not.toBeInTheDocument()
  })

  it('enters the Event Organiser console once an organisation is given', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /Login as Event Organiser/i }))
    await user.type(screen.getByLabelText(/organisation name/i), 'Acme Conferences')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText('Organiser home')).toBeInTheDocument()
  })

  it('routes straight to a role console for roles other than Event Organiser (no organisation prompt)', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /Login as Venue Staff/i }))

    expect(await screen.findByText('Venue Staff console')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
