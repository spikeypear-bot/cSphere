// @stories VS17 EC08 EO04 SO-C3 EC03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
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

function readiness() {
  return {
    eventId: EVENT_ID,
    approvalStatus: 'rejected',
    approvalNote: 'Recheck the venue setup',
    reviewedBy: 'so1',
    reviewedAt: '2026-09-22T02:00:00Z',
    completedItems: 1,
    totalItems: 6,
    items: [{
      itemKey: 'venue_booking', label: 'Venue booking is approved and suitable', checked: true,
      note: 'Approved by Venue Staff', checkedBy: 'ec1', checkedAt: '2026-09-22T01:00:00Z',
      updatedBy: 'ec1', updatedAt: '2026-09-22T01:00:00Z',
    }],
  }
}

function renderPage(basePath: string) {
  const previousFetch = globalThis.fetch
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/venue-assessments') ? Promise.resolve(jsonResponse(200, [])) : previousFetch(input, init))
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

describe('EventDetailsPage — shared event view and SO-C3 readiness view', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC-NEW2 AC24 (cross-cutting): shows event details to an Organiser without a Confirm button', async () => {
    seedSession('organiser')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, pendingEvent())))

    renderPage('/organiser')

    expect(await screen.findByRole('heading', { name: 'Q1 Town Hall' })).toBeInTheDocument()
    expect(screen.getByText('All-hands update')).toBeInTheDocument()
    expect(screen.getByText('ec1 (ec1@connectsphere.test)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument()
  })

  it('EC08 AC4 (happy): shows coordinator planning actions while the event is in Planning', async () => {
    seedSession('coordinator')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      return jsonResponse(200, url.endsWith('/readiness') ? readiness() : url.endsWith('/timeline') ? [] : pendingEvent())
    }))

    renderPage('/coordinator')

    expect(screen.queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Request equipment' })).toHaveAttribute(
      'href',
      `/coordinator/events/${EVENT_ID}/equipment-request`,
    )
    expect(await screen.findByText('1 of 6 items complete')).toBeInTheDocument()
    expect(screen.getByText(/Checked by ec1/)).toBeInTheDocument()
    expect(screen.getByText('Current approval: rejected')).toBeInTheDocument()
    expect(screen.getByText('Note: Recheck the venue setup')).toBeInTheDocument()
    expect(screen.getByText(/Reviewed by so1/)).toBeInTheDocument()
  })

  it('EC08 AC25, EC-NEW2 AC24 (negative): does not let an Event Coordinator confirm an event directly', async () => {
    seedSession('coordinator')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/events/${EVENT_ID}/readiness`) return jsonResponse(200, readiness())
      if (url === `/api/events/${EVENT_ID}/timeline`) return jsonResponse(200, [])
      if (url === `/api/events/${EVENT_ID}`) return jsonResponse(200, pendingEvent())
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    }))
    renderPage('/coordinator')

    expect(screen.queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument()
  })

  it('EC08 AC36 (failure): shows a clear error, not a crash, when the event cannot be loaded', async () => {
    seedSession('organiser')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(404, { message: 'Event not found: event-1' })))

    renderPage('/organiser')

    expect(await screen.findByRole('alert')).toHaveTextContent('Event not found: event-1')
  })
})
