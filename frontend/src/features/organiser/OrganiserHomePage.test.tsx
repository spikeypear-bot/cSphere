import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { OrganiserHomePage } from './OrganiserHomePage'

function seedSession() {
  window.localStorage.setItem(
    'connectsphere.session',
    JSON.stringify({ role: 'organiser', organisation: 'Acme Conferences' }),
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response
}

function renderHome(initialState?: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/organiser', state: initialState }]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser" element={<OrganiserHomePage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('OrganiserHomePage — EO15 (view requests) and EO02 (submission confirmation)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows a success confirmation once, right after arriving from a successful submission', async () => {
    renderHome({ justSubmitted: true })

    expect(
      await screen.findByText('Your event request has been submitted successfully.'),
    ).toBeInTheDocument()
  })

  it('shows no confirmation on an ordinary visit', async () => {
    renderHome()

    await screen.findByText('Your event requests')
    expect(
      screen.queryByText('Your event request has been submitted successfully.'),
    ).not.toBeInTheDocument()
  })
})
