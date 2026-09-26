import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

const EMPTY: ReviewQueue = { needsReview: [], awaitingOrganiser: [], unassigned: [] }

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
        unassigned: [request('r3', 'Offsite', 'pending', null)],
      }),
    })
    renderQueue()

    const mine = await screen.findByRole('region', { name: /Needs your review/ })
    expect(within(mine).getByText('Town Hall')).toBeInTheDocument()
    expect(within(mine).getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/coordinator/requests/r1')

    const waiting = screen.getByRole('region', { name: /Waiting for the organiser/ })
    expect(within(waiting).getByText('Gala')).toBeInTheDocument()
    expect(within(waiting).getByText('Clarification required')).toBeInTheDocument()

    const unassigned = screen.getByRole('region', { name: /Unassigned/ })
    expect(within(unassigned).getByRole('button', { name: 'Assign to me' })).toBeInTheDocument()
  })

  it('assigning an unassigned request to myself moves it into my review list', async () => {
    let assigned = false
    const calls = stubApi({
      'GET /api/event-requests/queue': () => jsonResponse(200, assigned
        ? { ...EMPTY, needsReview: [request('r3', 'Offsite')] }
        : { ...EMPTY, unassigned: [request('r3', 'Offsite', 'pending', null)] }),
      'POST /api/event-requests/r3/assign-coordinator': () => {
        assigned = true
        return jsonResponse(200, request('r3', 'Offsite'))
      },
    })
    const user = userEvent.setup()
    renderQueue()

    await user.click(await screen.findByRole('button', { name: 'Assign to me' }))

    expect(await screen.findByRole('region', { name: /Needs your review/ })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ coordinatorUserId: COORDINATOR_ID })
  })

  it('shows an assignment failure inline without losing the list', async () => {
    stubApi({
      'GET /api/event-requests/queue': () => jsonResponse(200, { ...EMPTY, unassigned: [request('r3', 'Offsite', 'pending', null)] }),
      'POST /api/event-requests/r3/assign-coordinator': () => jsonResponse(422, { message: 'Not an Event Coordinator' }),
    })
    const user = userEvent.setup()
    renderQueue()

    await user.click(await screen.findByRole('button', { name: 'Assign to me' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Not an Event Coordinator')
    expect(screen.getByText('Offsite')).toBeInTheDocument()
  })
})
