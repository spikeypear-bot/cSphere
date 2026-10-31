// @stories AT09
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { NotificationBell } from './NotificationBell'

const changed = {
  notificationId: 'change-1',
  type: 'attendee_event_changed',
  eventRequestId: null,
  eventId: 'event-1',
  eventName: 'Community conference',
  newStatus: null,
  reason: null,
  coordinatorName: null,
  coordinatorEmail: null,
  isReassignment: null,
  occurredAt: '2026-10-30T09:15:00+08:00',
  read: false,
  linkPath: '/attendee/events/event-1',
  message: 'Important event arrangements have changed.',
  changes: [
    { field: 'startDatetime', label: 'Start', previousValue: '10 Mar 2027, 9:00 AM', updatedValue: '10 Mar 2027, 10:00 AM' },
    { field: 'attendeeInstructions', label: 'Attendee instructions', previousValue: '—', updatedValue: 'Join online at the event link.' },
  ],
}

function renderBell() {
  window.localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'access-1', refreshToken: 'refresh-1', userId: 'attendee-1',
    username: 'att1', role: 'attendee', organisation: 'Acme Pte Ltd',
  }))
  return render(
    <MemoryRouter initialEntries={['/attendee']}>
      <SessionProvider>
        <Routes>
          <Route path="/attendee" element={<NotificationBell />} />
          <Route path="/attendee/events/:eventId" element={<div>Current event details</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

describe('AT09 attendee event notifications', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('AT09 AC9, AC11, AC12, AC13 (happy): shows one saved before/after comparison and opens current event details', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [changed])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      if (url === '/api/notifications/change-1/read') return jsonResponse(200, { ...changed, read: true })
      throw new Error(`Unexpected fetch: ${url} ${init?.method ?? 'GET'}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('Important updates for "Community conference"')).toBeInTheDocument()
    expect(screen.getByText('Start: 10 Mar 2027, 9:00 AM → 10 Mar 2027, 10:00 AM')).toBeInTheDocument()
    expect(screen.getByText('Attendee instructions: — → Join online at the event link.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Important updates for/ }))
    expect(await screen.findByText('Current event details')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/notifications/change-1/read', expect.objectContaining({ method: 'POST' }),
    ))
  })

  it('AT09 AC10 (happy): shows cancellation status, original event time, and the supplied reason', async () => {
    const cancelled = {
      ...changed,
      notificationId: 'cancel-1',
      type: 'attendee_event_cancelled',
      reason: 'The organiser cancelled the event.',
      message: 'This event has been cancelled.',
      changes: null,
      originalEventStartDatetime: '2027-03-10T09:00:00+08:00',
      originalEventEndDatetime: '2027-03-10T12:00:00+08:00',
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/notifications') return jsonResponse(200, [cancelled])
      if (url === '/api/notifications/unread-count') return jsonResponse(200, { count: 1 })
      throw new Error(`Unexpected fetch: ${url}`)
    }))
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByLabelText(/Notifications/))

    expect(await screen.findByText('"Community conference" has been cancelled')).toBeInTheDocument()
    expect(screen.getByText(/Original date\/time: 10 Mar 2027, 09:00 am/)).toBeInTheDocument()
    expect(screen.getByText('Reason: The organiser cancelled the event.')).toBeInTheDocument()
  })
})
