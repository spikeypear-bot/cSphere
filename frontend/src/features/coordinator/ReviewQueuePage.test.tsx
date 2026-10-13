import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ReviewQueuePage, type ReviewQueue } from './ReviewQueuePage'

const COORDINATOR_ID = 'ec-1'

function request(id: string, name: string, status = 'pending', coordinatorId: string | null = COORDINATOR_ID) {
  return {
    requestId: id, eventName: name, organisation: 'Acme Pte Ltd', expectedAttendance: 150,
    startDatetime: '2026-12-01T09:00:00Z', status, coordinatorId, rejectionReason: null,
    createdAt: '2026-09-20T09:00:00Z', updatedAt: '2026-09-21T09:00:00Z',
  }
}

const EMPTY: ReviewQueue = { needsReview: [], awaitingOrganiser: [] }

function renderQueue() {
  return render(
    <MemoryRouter initialEntries={['/coordinator/review-queue']}>
      <SessionProvider>
        <ReviewQueuePage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('ReviewQueuePage (EC02 queue)', () => {
  beforeEach(() => seedSession('coordinator', COORDINATOR_ID))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows an empty state when nothing is waiting', async () => {
    stubApi({ 'GET /api/event-requests/queue': () => jsonResponse(200, EMPTY) })
    renderQueue()
    expect(await screen.findByText('Nothing is waiting for review right now.')).toBeInTheDocument()
  })

  it('groups my requests by who needs to act, and links each to its review screen', async () => {
    stubApi({
      'GET /api/event-requests/queue': () => jsonResponse(200, {
        needsReview: [request('r1', 'Town Hall')],
        awaitingOrganiser: [request('r2', 'Gala', 'clarification_required')],
      }),
    })
    renderQueue()

    const mine = await screen.findByRole('region', { name: /Needs your review/ })
    expect(within(mine).getByText('Town Hall')).toBeInTheDocument()
    expect(within(mine).getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/coordinator/requests/r1')

    const waiting = screen.getByRole('region', { name: /Waiting for the organiser/ })
    expect(within(waiting).getByText('Gala')).toBeInTheDocument()
    expect(within(waiting).getByText('Clarification required')).toBeInTheDocument()
    expect(within(waiting).getByRole('link', { name: 'View' })).toHaveAttribute('href', '/coordinator/requests/r2')
  })

  it('shows no unassigned requests and offers no way to assign one (ELC-C6)', async () => {
    const calls = stubApi({
      // Even if a server still sent unassigned requests, they are not the coordinator's to see.
      'GET /api/event-requests/queue': () => jsonResponse(200, {
        needsReview: [request('r1', 'Town Hall')],
        awaitingOrganiser: [],
        unassigned: [request('r3', 'Offsite', 'pending', null)],
      }),
    })
    renderQueue()

    await screen.findByRole('region', { name: /Needs your review/ })
    expect(screen.queryByText('Offsite')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /Unassigned/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /assign/i })).not.toBeInTheDocument()
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual(['GET /api/event-requests/queue'])
  })
})
