import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { NotificationBell } from './NotificationBell'

function seedSession() {
  window.localStorage.setItem(
    'connectsphere.auth',
    JSON.stringify({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      userId: 'organiser-1',
      username: 'eo1',
      role: 'organiser',
      organisation: 'Acme Conferences',
    }),
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

const STATUS_CHANGE_NOTIFICATION = {
  notificationId: 'n1',
  type: 'status_change',
  eventRequestId: 'req-1',
  eventId: 'event-1',
  eventName: 'Alumni Homecoming',
  newStatus: 'approved',
  reason: null,
  coordinatorName: null,
  coordinatorEmail: null,
  isReassignment: null,
  occurredAt: new Date().toISOString(),
  read: false,
  linkPath: '/organiser/events/event-1',
}

const REJECTED_NOTIFICATION = {
  ...STATUS_CHANGE_NOTIFICATION,
  notificationId: 'n2',
  eventId: null,
  newStatus: 'rejected',
  reason: 'No venue available for these dates',
  linkPath: '/organiser/requests/req-1',
  read: true,
}

function renderBell() {
  return render(
    <MemoryRouter initialEntries={['/organiser']}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser" element={<NotificationBell />} />
          <Route path="/organiser/events/:eventId" element={<div>Event details page</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('NotificationBell — EO09/EO19', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows an unread-count badge from the unread-count endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [STATUS_CHANGE_NOTIFICATION])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))

    renderBell()

    expect(await screen.findByLabelText('Notifications, 1 unread')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('lists notifications and distinguishes read from unread, including the rejection reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') {
        return jsonResponse(200, [STATUS_CHANGE_NOTIFICATION, REJECTED_NOTIFICATION])
      }
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('"Alumni Homecoming" is now Approved')).toBeInTheDocument()
    expect(screen.getByText('Reason: No venue available for these dates')).toBeInTheDocument()

    const unreadItem = screen.getByText('"Alumni Homecoming" is now Approved').closest('button')
    const readItem = screen.getByText('Reason: No venue available for these dates').closest('button')
    expect(unreadItem).toHaveAttribute('data-read', 'false')
    expect(readItem).toHaveAttribute('data-read', 'true')
  })

  it('marks a notification as read and navigates to its link when clicked', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [STATUS_CHANGE_NOTIFICATION])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      if (url === '/api/notifications/n1/read' && init?.method === 'POST') {
        return jsonResponse(200, { ...STATUS_CHANGE_NOTIFICATION, read: true })
      }
      throw new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))
    await user.click(await screen.findByText('"Alumni Homecoming" is now Approved'))

    expect(await screen.findByText('Event details page')).toBeInTheDocument()
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/notifications/n1/read',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
  })

  it('shows an empty state with nothing to fail on when there are no notifications', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 0 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText('Notifications'))
    expect(await screen.findByText('Nothing yet.')).toBeInTheDocument()
  })
})
