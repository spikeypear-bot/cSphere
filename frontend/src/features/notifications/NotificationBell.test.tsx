// @stories EO09 EO19 EC-C3 EO07 AT01
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
    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument()
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

describe('EO07 NotificationBell — cancellation notifications', () => {
  beforeEach(() => {
    window.localStorage.clear()
    seedSession()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  function stubList(list: unknown[]) {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, list)
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: list.length })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
  }

  const base = { ...STATUS_CHANGE_NOTIFICATION, eventName: 'Town Hall', newStatus: null }

  it('AT01 AC9 (happy): the attendee receives a notification with the registration reference and event link', async () => {
    stubList([{ ...base, notificationId: 'at1', type: 'attendee_registration_confirmed', message: 'Your registration is confirmed. Reference: CS-123456789ABC', linkPath: '/attendee/events/event-1' }])
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText(/Notifications/))
    expect(await screen.findByText('Your registration for "Town Hall" is confirmed')).toBeInTheDocument()
    expect(screen.getByText('“Your registration is confirmed. Reference: CS-123456789ABC”')).toBeInTheDocument()
  })

  it('EO07 AC2, AC7 (happy): the coordinator sees a cancelled request and a cancellation request, each with the organiser\'s reason', async () => {
    stubList([
      { ...base, notificationId: 'c1', type: 'request_cancelled', eventId: null, message: 'Budget withdrawn',
        linkPath: '/coordinator/requests/req-1' },
      { ...base, notificationId: 'c2', type: 'event_cancellation_requested', message: 'Venue flooded',
        linkPath: '/coordinator/events/event-1' },
    ])
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('The organiser cancelled the request "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText('“Budget withdrawn”')).toBeInTheDocument()
    expect(screen.getByText('Cancellation requested for "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText('“Venue flooded”')).toBeInTheDocument()
  })

  it('EO07 AC10 (happy): the organiser sees the outcome, with the coordinator\'s reason when it was declined', async () => {
    stubList([
      { ...base, notificationId: 'o1', type: 'event_cancellation_approved',
        message: 'Town Hall has been cancelled as you asked.' },
      { ...base, notificationId: 'o2', type: 'event_cancellation_declined',
        message: 'Cancellation declined: Deposit is non-refundable' },
    ])
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('"Town Hall" has been cancelled')).toBeInTheDocument()
    expect(screen.getByText('Cancellation of "Town Hall" was declined')).toBeInTheDocument()
    expect(screen.getByText('“Cancellation declined: Deposit is non-refundable”')).toBeInTheDocument()
  })

  it('EO07 AC10 (happy): Venue Staff and Technical Support see that the event is cancelled', async () => {
    stubList([{ ...base, notificationId: 's1', type: 'event_cancelled', linkPath: null,
      message: 'Town Hall (2027-03-10) has been cancelled. Its venue bookings and equipment are no longer needed.' }])
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('"Town Hall" has been cancelled')).toBeInTheDocument()
    expect(screen.getByText(/no longer needed/)).toBeInTheDocument()
  })
})
