import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { ReviewQueuePage } from './ReviewQueuePage'

const COORDINATOR_ID = 'ec-1'

function seedSession() {
  window.localStorage.setItem(
    'connectsphere.auth',
    JSON.stringify({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      userId: COORDINATOR_ID,
      username: 'ec1',
      role: 'coordinator',
      organisation: 'ConnectSphere',
    }),
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

function unassignedRequest(): {
  requestId: string
  eventName: string
  organisation: string
  expectedAttendance: number
  startDatetime: string
  status: string
  coordinatorId: string | null
  rejectionReason: string | null
} {
  return {
    requestId: 'req-1',
    eventName: 'Alumni Homecoming',
    organisation: 'Acme Conferences',
    expectedAttendance: 150,
    startDatetime: '2026-12-01T09:00:00Z',
    status: 'pending',
    coordinatorId: null,
    rejectionReason: null,
  }
}

function renderQueue() {
  return render(
    <MemoryRouter initialEntries={['/coordinator/review-queue']}>
      <SessionProvider>
        <ReviewQueuePage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('ReviewQueuePage — EC01/EC02 minimal slice backing EO09/EO19', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows an empty state when nothing is waiting for review', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    renderQueue()
    expect(await screen.findByText('Nothing is waiting for review right now.')).toBeInTheDocument()
  })

  it('offers "Assign to me" for an unassigned request, then Approve/Reject once assigned', async () => {
    let current = unassignedRequest()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests/queue' && (!init || init.method === undefined || init.method === 'GET')) {
        return jsonResponse(200, [current])
      }
      if (url === '/api/event-requests/req-1/assign-coordinator' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body))
        expect(body.coordinatorUserId).toBe(COORDINATOR_ID)
        current = { ...current, coordinatorId: COORDINATOR_ID }
        return jsonResponse(200, current)
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderQueue()

    expect(await screen.findByText('Unassigned')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Assign to me' }))

    expect(await screen.findByText('Assigned to you')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('approves an assigned request', async () => {
    const assigned = { ...unassignedRequest(), coordinatorId: COORDINATOR_ID }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests/queue') {
        return jsonResponse(200, [assigned])
      }
      if (url === '/api/event-requests/req-1/approve' && init?.method === 'POST') {
        return jsonResponse(200, { ...assigned, status: 'approved' })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderQueue()

    await user.click(await screen.findByRole('button', { name: 'Approve' }))

    expect(fetchMock).toHaveBeenCalledWith('/api/event-requests/req-1/approve', expect.objectContaining({ method: 'POST' }))
  })

  it('rejects with a required reason', async () => {
    const assigned = { ...unassignedRequest(), coordinatorId: COORDINATOR_ID }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests/queue') return jsonResponse(200, [assigned])
      if (url === '/api/event-requests/req-1/reject' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body))
        expect(body.reason).toBe('Venue unavailable')
        return jsonResponse(200, { ...assigned, status: 'rejected', rejectionReason: body.reason })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderQueue()

    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    // The confirm button starts disabled with no reason typed yet.
    expect(screen.getByRole('button', { name: 'Confirm rejection' })).toBeDisabled()

    await user.type(screen.getByPlaceholderText('Why is this request being rejected?'), 'Venue unavailable')
    await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/event-requests/req-1/reject',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('surfaces a server-side error inline without crashing the page', async () => {
    const assigned = { ...unassignedRequest(), coordinatorId: 'someone-else' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/event-requests/queue') return jsonResponse(200, [assigned])
      if (url === '/api/event-requests/req-1/assign-coordinator') {
        return jsonResponse(422, { message: 'User is not an Event Coordinator' })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    }))
    const user = userEvent.setup()
    renderQueue()

    expect(await screen.findByText('Assigned to another coordinator')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Assign to me' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('User is not an Event Coordinator')
  })
})
