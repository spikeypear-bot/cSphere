import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { EventDetailsPage } from './EventDetailsPage'

const EVENT_ID = 'event-1'

function seedSession(role: 'organiser' | 'coordinator') {
  window.localStorage.setItem(
    'connectsphere.auth',
    JSON.stringify({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      userId: 'user-1',
      username: role === 'organiser' ? 'eo1' : 'ec1',
      role,
      organisation: role === 'organiser' ? 'Acme Conferences' : 'ConnectSphere',
    }),
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

function pendingEvent() {
  return {
    eventId: EVENT_ID,
    eventName: 'Q1 Town Hall',
    purpose: 'All-hands update',
    description: null,
    startDatetime: '2026-12-01T09:00:00Z',
    endDatetime: '2026-12-01T11:00:00Z',
    expectedAttendance: 150,
    venueId: null,
    accessibilityNeeds: [],
    registrationNeeds: null,
    organisation: 'Acme Conferences',
    venueRequirements: 'Theatre-style seating for 150',
    equipmentRequirements: null,
    status: 'pending',
    coordinatorName: 'ec1',
    coordinatorEmail: 'ec1@connectsphere.test',
  }
}

function renderPage(basePath: string) {
  return render(
    <MemoryRouter initialEntries={[`${basePath}/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes>
          <Route path={`${basePath}/events/:eventId`} element={<EventDetailsPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EventDetailsPage — EO09 confirmed-arrangements view and Confirm action', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows event details to an Organiser without a Confirm button', async () => {
    seedSession('organiser')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, pendingEvent())))

    renderPage('/organiser')

    expect(await screen.findByRole('heading', { name: 'Q1 Town Hall' })).toBeInTheDocument()
    expect(screen.getByText('All-hands update')).toBeInTheDocument()
    expect(screen.getByText('ec1 (ec1@connectsphere.test)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument()
  })

  it('shows a Confirm button to the assigned Coordinator while pending', async () => {
    seedSession('coordinator')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, pendingEvent())))

    renderPage('/coordinator')

    expect(await screen.findByRole('button', { name: 'Confirm event' })).toBeInTheDocument()
  })

  it('confirming reloads the event and hides the Confirm button once confirmed', async () => {
    seedSession('coordinator')
    let confirmed = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/events/${EVENT_ID}/confirm` && init?.method === 'POST') {
        confirmed = true
        return jsonResponse(200, { ...pendingEvent(), status: 'confirmed' })
      }
      if (url === `/api/events/${EVENT_ID}`) {
        return jsonResponse(200, confirmed ? { ...pendingEvent(), status: 'confirmed' } : pendingEvent())
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    }))
    const user = userEvent.setup()
    renderPage('/coordinator')

    await user.click(await screen.findByRole('button', { name: 'Confirm event' }))

    expect(await screen.findByText('Confirmed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument()
  })

  it('shows a clear error, not a crash, when the event cannot be loaded', async () => {
    seedSession('organiser')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(404, { message: 'Event not found: event-1' })))

    renderPage('/organiser')

    expect(await screen.findByRole('alert')).toHaveTextContent('Event not found: event-1')
  })
})
