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

const VENUE_UNAVAILABLE_NOTIFICATION = {
  ...STATUS_CHANGE_NOTIFICATION,
  notificationId: 'n3',
  type: 'venue_unavailable',
  reason: 'Maintenance',
  message: 'Affected booking at Downtown Hall (2027-04-01T10:00+08:00 to 2027-04-01T12:00+08:00). Alternative venue arrangements required.',
  linkPath: '/coordinator/events/event-1/venue-booking',
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

    expect(await screen.findByText('"Alumni Homecoming" is now in Planning')).toBeInTheDocument()
    expect(screen.getByText('Reason: No venue available for these dates')).toBeInTheDocument()

    const unreadItem = screen.getByText('"Alumni Homecoming" is now in Planning').closest('button')
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
    await user.click(await screen.findByText('"Alumni Homecoming" is now in Planning'))

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

  it('EC-C4 identifies the affected venue booking in the immediate notification', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [VENUE_UNAVAILABLE_NOTIFICATION])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('A venue booking for "Alumni Homecoming" is unavailable')).toBeInTheDocument()
    expect(screen.getByText(VENUE_UNAVAILABLE_NOTIFICATION.message)).toBeInTheDocument()
  })

  it('EC-C3 AC1 (happy): a hold reminder says it is expiring soon, shows its details and opens the venue booking page', async () => {
    const reminder = {
      ...STATUS_CHANGE_NOTIFICATION,
      notificationId: 'n4',
      type: 'venue_hold_expiring',
      newStatus: null,
      message: 'The tentative hold on Downtown Hall for "Alumni Homecoming" expires on 2 Mar 2027, 4:00 AM (Singapore time).',
      linkPath: '/coordinator/events/event-1/venue-booking',
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [reminder])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      if (url === '/api/notifications/n4/read' && init?.method === 'POST') return jsonResponse(200, { ...reminder, read: true })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/coordinator']}>
        <SessionProvider>
          <Routes>
            <Route path="/coordinator" element={<NotificationBell />} />
            <Route path="/coordinator/events/:eventId/venue-booking" element={<div>Venue booking page</div>} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    await user.click(await screen.findByLabelText(/Notifications/))
    expect(await screen.findByText('Tentative venue hold expiring soon for "Alumni Homecoming"')).toBeInTheDocument()
    expect(screen.getByText(reminder.message)).toBeInTheDocument()
    await user.click(screen.getByText('Tentative venue hold expiring soon for "Alumni Homecoming"'))
    expect(await screen.findByText('Venue booking page')).toBeInTheDocument()
  })
})
