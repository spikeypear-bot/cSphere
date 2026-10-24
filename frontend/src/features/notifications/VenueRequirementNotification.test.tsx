// @stories VS17
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { NotificationBell } from './NotificationBell'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })
describe('VS17 venue requirement notifications', () => {
  it.each([['venue_requirement_changed', 'required'], ['venue_requirement_reviewed', 'recorded']])('VS17 (happy): %s shows only its header and timestamp, retaining read and navigation behaviour', async (type, action) => {
    localStorage.setItem('connectsphere.auth', JSON.stringify({ accessToken: 'access', refreshToken: 'refresh', userId: 'vs1', username: 'vs1', role: 'venue-staff', organisation: 'ConnectSphere' }))
    const notice = { notificationId: 'n1', type, eventName: 'Conference', occurredAt: new Date().toISOString(), read: false, message: 'Expected attendance: 100 → 200. Reserved 09:00 to 12:00.', linkPath: '/venue-staff/bookings/b1' }
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const body = url === '/api/notifications' ? [notice] : url.endsWith('/unread-count') ? { count: 1 } : { ...notice, read: true }
      return { ok: true, status: 200, text: async () => JSON.stringify(body) } as Response
    })
    vi.stubGlobal('fetch', fetcher)
    render(<MemoryRouter><SessionProvider><Routes><Route path="/" element={<NotificationBell />} /><Route path="/venue-staff/bookings/b1" element={<div>Affected booking details</div>} /></Routes></SessionProvider></MemoryRouter>)
    await userEvent.click(await screen.findByLabelText('Notifications, 1 unread'))
    expect(screen.queryByText(/Expected attendance: 100 → 200/)).not.toBeInTheDocument()
    expect(screen.getByText('just now')).toBeInTheDocument()
    await userEvent.click(screen.getByText(`Venue requirement review ${action} for "Conference"`))
    expect(await screen.findByText('Affected booking details')).toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledWith('/api/notifications/n1/read', expect.objectContaining({ method: 'POST' }))
  })
})
