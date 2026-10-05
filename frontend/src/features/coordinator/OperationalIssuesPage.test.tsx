import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { OperationalIssuesPage } from './OperationalIssuesPage'

describe('OperationalIssuesPage (VS13)', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows affected managed events without implying bookings were changed', async () => {
    stubApi({
      'GET /api/coordinator/operational-issues': () => jsonResponse(200, [{
        issueId: 'issue-1',
        venueId: 'venue-1',
        venueAddress: '1 Harbour Road',
        description: 'Air conditioning failure',
        affectedFrom: '2027-03-10T08:00:00Z',
        affectedUntil: '2027-03-10T13:00:00Z',
        createdAt: '2027-03-01T04:00:00Z',
        overlappingEvents: [{
          eventId: 'event-1',
          eventName: 'Town Hall',
          startDatetime: '2027-03-10T09:00:00Z',
          endDatetime: '2027-03-10T12:00:00Z',
        }],
      }]),
    })

    render(
      <MemoryRouter>
        <SessionProvider><OperationalIssuesPage /></SessionProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: '1 Harbour Road' })).toBeInTheDocument()
    expect(screen.getByText('Air conditioning failure')).toBeInTheDocument()
    expect(screen.getByText('Town Hall', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/Existing bookings remain unchanged/)).toBeInTheDocument()
  })

  it('shows a clear empty state when no managed venue has an issue', async () => {
    stubApi({
      'GET /api/coordinator/operational-issues': () => jsonResponse(200, []),
    })

    render(
      <MemoryRouter>
        <SessionProvider><OperationalIssuesPage /></SessionProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByText('No operational issues affect your approved venue bookings.'))
      .toBeInTheDocument()
  })

  it('shows a retrieval error', async () => {
    const calls = stubApi({
      'GET /api/coordinator/operational-issues': () => jsonResponse(500, { message: 'Service unavailable.' }),
    })

    render(
      <MemoryRouter>
        <SessionProvider><OperationalIssuesPage /></SessionProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable.')
    expect(calls).toHaveLength(1)
  })
})
