// @stories AT03 AT01
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { AttendeeEventDetailsPage } from '../attendee/AttendeeEventDetailsPage'
import { NotificationBell } from './NotificationBell'

const event = {
  eventId: 'e1', eventName: 'Community conference', status: 'confirmed',
  startDatetime: '2027-03-01T01:00:00Z', endDatetime: '2027-03-01T09:00:00Z',
  updatedAt: null, accessibilityInformation: [], venues: [], registrationRequired: true,
  availablePlaces: 5, registrationCapacity: 10, registrationOpensAt: '2020-01-01T00:00:00Z',
  registrationClosesAt: '2027-03-01T01:00:00Z', registrationReference: null, registeredAt: null,
}
const notice = { notificationId: 'n1', type: 'attendee_registration_confirmed',
  eventId: 'e1', eventName: event.eventName, read: false, occurredAt: '2026-12-01T00:00:00Z',
  message: 'Your registration is confirmed. Reference: CS-ONE', linkPath: '/attendee/my-registrations/r1' }
const response = (data: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(data) })

function renderPage() {
  return render(<MemoryRouter initialEntries={['/attendee/events/e1']}><SessionProvider>
    <NotificationBell />
    <Routes><Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} /></Routes>
  </SessionProvider></MemoryRouter>)
}
beforeEach(() => {
  window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh',
    userId: 'att1', username: 'att1', role: 'attendee', organisation: 'Acme' }))
})
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); window.localStorage.clear() })

describe('AT03 immediate registration notification refresh', () => {
  it('AT03 (failure): a confirmation delivered later appears automatically without a local registration event or manual refresh', async () => {
    vi.useFakeTimers()
    let delivered = false
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/notifications') return response(delivered ? [notice] : [])
      if (url === '/api/notifications/unread-count') return response({ count: delivered ? 1 : 0 })
      return response(event)
    }))
    renderPage()
    await act(async () => {})
    expect(screen.queryByRole('button', { name: 'Notifications, 1 unread' })).not.toBeInTheDocument()
    delivered = true
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Notifications, 1 unread' }))
    expect(screen.getByText('Your registration for "Community conference" is confirmed')).toBeInTheDocument()
  })
  it('AT03 (happy): saved registration immediately refreshes the bell, and an older empty response cannot hide the notification', async () => {
    let saved = false, reads = 0
    let resolveInitial!: (value: ReturnType<typeof response>) => void
    const initialList = new Promise<ReturnType<typeof response>>(resolve => { resolveInitial = resolve })
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
      if (url === '/api/notifications') { reads++; return reads === 1 ? initialList : response([notice]) }
      if (url === '/api/notifications/unread-count') return response({ count: saved ? 1 : 0 })
      if (options.method === 'POST') { saved = true; return response({ registrationId: 'r1', reference: 'CS-ONE', registeredAt: '2026-12-01T00:00:00Z' }) }
      if (url === '/api/attendee/registrations/r1') return response({ ...event, registrationId: 'r1', reference: 'CS-ONE',
        registeredAt: '2026-12-01T00:00:00Z', registrationStatus: 'confirmed', eventStatus: 'confirmed',
        eventDetailsAvailable: true, withdrawalAvailable: true, withdrawalReason: 'Before event start.' })
      return response(event)
    }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    const bell = await screen.findByRole('button', { name: 'Notifications, 1 unread' })
    expect(reads).toBe(2)
    fireEvent.click(bell)
    expect(screen.getByText('Your registration for "Community conference" is confirmed')).toBeInTheDocument()
    await act(async () => { resolveInitial(response([])) })
    expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    expect(screen.getByText('Your registration for "Community conference" is confirmed')).toBeInTheDocument()
  })

  it('AT03 (negative): a refused registration neither refreshes the bell as a success nor displays a confirmation', async () => {
    let reads = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit) => {
      if (url === '/api/notifications') { reads++; return response([]) }
      if (url === '/api/notifications/unread-count') return response({ count: 0 })
      if (options.method === 'POST') return response({ message: 'This event is full.' }, 409)
      return response(event)
    }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This event is full.')
    expect(reads).toBe(1)
    expect(screen.queryByRole('button', { name: /Notifications, .* unread/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Registration confirmation' })).not.toBeInTheDocument()
  })
})
