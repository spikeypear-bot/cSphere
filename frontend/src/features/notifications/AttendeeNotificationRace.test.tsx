// @stories AT03 EO19
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { NotificationBell } from './NotificationBell'

const first = { notificationId: 'n1', type: 'attendee_registration_confirmed', eventId: 'e1', eventName: 'First event',
  read: false, occurredAt: '2026-12-01T00:00:00Z', linkPath: '/attendee/my-registrations/r1' }
const second = { ...first, notificationId: 'n2', eventName: 'Second event' }
const response = (body: unknown) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) }) as Response
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); window.localStorage.clear() })

describe('AT03 attendee notification read ordering', () => {
  it('AT03 (boundary): notification reads slower than five seconds can still populate the bell', async () => {
    vi.useFakeTimers()
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh',
      userId: 'att1', username: 'att1', role: 'attendee', organisation: 'Acme' }))
    let lists = 0, counts = 0
    let list!: (value: Response) => void, count!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url === '/api/notifications') return ++lists === 1 ? new Promise(resolve => { list = resolve }) : new Promise<Response>(() => {})
      if (url.endsWith('/unread-count')) return ++counts === 1 ? new Promise(resolve => { count = resolve }) : new Promise<Response>(() => {})
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<MemoryRouter><SessionProvider><NotificationBell /></SessionProvider></MemoryRouter>)
    await act(async () => {})
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    await act(async () => { list(response([first])); count(response({ count: 1 })) })
    expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Notifications, 1 unread' }))
    expect(screen.getByText('Your registration for "First event" is confirmed')).toBeInTheDocument()
  })
  it('AT03 EO19 (conflict): an old poll cannot restore unread state after a receipt is marked read', async () => {
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh',
      userId: 'att1', username: 'att1', role: 'attendee', organisation: 'Acme' }))
    let lists = 0, counts = 0
    let list!: (value: Response) => void, count!: (value: Response) => void, saved!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url === '/api/notifications') return ++lists === 1 ? Promise.resolve(response([first, second])) : new Promise(resolve => { list = resolve })
      if (url.endsWith('/unread-count')) return ++counts === 1 ? Promise.resolve(response({ count: 2 })) : new Promise(resolve => { count = resolve })
      if (url === '/api/notifications/n1/read') return new Promise(resolve => { saved = resolve })
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<MemoryRouter><SessionProvider><NotificationBell /><Routes>
      <Route path="/" element={<p>Home</p>} /><Route path="/attendee/my-registrations/r1" element={<h1>Saved record</h1>} />
    </Routes></SessionProvider></MemoryRouter>)
    await screen.findByRole('button', { name: 'Notifications, 2 unread' })
    fireEvent(window, new Event('focus'))
    await waitFor(() => expect(list).toBeTypeOf('function'))
    fireEvent.click(screen.getByRole('button', { name: 'Notifications, 2 unread' }))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('button', { name: /First event/ }))
    await waitFor(() => expect(saved).toBeTypeOf('function'))
    await act(async () => saved(response({ ...first, read: true })))
    await act(async () => { list(response([first, second])); count(response({ count: 2 })) })
    expect(screen.getByRole('heading', { name: 'Saved record' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Notifications, 1 unread' }))
    expect(within(screen.getByRole('menu')).getByRole('button', { name: /First event/ })).toHaveAttribute('data-read', 'true')
    expect(within(screen.getByRole('menu')).getByRole('button', { name: /Second event/ })).toHaveAttribute('data-read', 'false')
  })
})
