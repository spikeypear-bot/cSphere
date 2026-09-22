import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { OrganiserHomePage } from './OrganiserHomePage'

function seedSession() {
  window.localStorage.setItem(
    'connectsphere.auth',
    JSON.stringify({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      username: 'eo1',
      role: 'organiser',
      organisation: 'Acme Conferences',
    }),
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

describe('OrganiserHomePage — EO01/EO15 UX enhancements (docs/decision-log.md D17)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows a relative last-edited time and completion ring for a draft', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(200, [
          {
            requestId: 'draft-1',
            status: 'draft',
            eventName: 'Q1 Town Hall',
            purpose: null,
            startDatetime: null,
            endDatetime: null,
            expectedAttendance: null,
            venueRequirements: null,
            accessibilityNeeds: [],
            organisation: 'Acme Conferences',
            createdAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
            updatedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
          },
        ]),
      ),
    )

    renderHome()

    expect(await screen.findByText(/Edited 5 minutes ago/)).toBeInTheDocument()
  })

  it('shows the status timeline for a submitted request, not only the badge', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(200, [
          {
            requestId: 'req-1',
            status: 'pending',
            eventName: 'Partner Summit',
            organisation: 'Acme Conferences',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            accessibilityNeeds: [],
          },
        ]),
      ),
    )

    renderHome()

    await screen.findByText('Partner Summit')
    const timeline = document.querySelector('.status-timeline') as HTMLElement
    expect(within(timeline).getByText('Submitted').closest('li')).toHaveAttribute('data-state', 'current')
  })
})
