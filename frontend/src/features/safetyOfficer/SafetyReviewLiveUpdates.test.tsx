// @stories EC08 SO-C1
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { seedSession, stubApi, jsonResponse } from '../../test/apiStubs'
import { NotificationBell } from '../notifications/NotificationBell'
import { SafetyOfficerHomePage } from './SafetyOfficerHomePage'
import { SafetyReviewsPage } from './SafetyReviewsPage'

afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); window.localStorage.clear() })

describe('EC08 SO-C1 safety review updates without reloading', () => {
  it.each([
    { Page: SafetyOfficerHomePage, queue: 'Awaiting your review' },
    { Page: SafetyReviewsPage, queue: 'Waiting for your safety review' },
  ])('EC08 SO-C1 (happy): $queue gains a submitted event and loses a withdrawn event with matching notices', async ({ Page, queue }) => {
    vi.useFakeTimers()
    seedSession('safety-officer')
    let sent = false
    let withdrawn = false
    const event = { eventId: 'live-event', eventName: 'Live workshop',
      startDatetime: '2030-04-01T01:00:00Z', endDatetime: '2030-04-01T04:00:00Z', venueAddress: 'Main hall' }
    const notices = () => !sent ? [] : [{ notificationId: 'notice-1', eventId: event.eventId, eventName: event.eventName,
      type: withdrawn ? 'safety_review_withdrawn' : 'safety_review_requested', read: false,
      occurredAt: new Date().toISOString(), reason: withdrawn ? 'Attendance has changed' : null,
      linkPath: withdrawn ? '/safety-officer/reviews' : '/safety-officer/events/live-event' }]
    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(200, [{ ...event, status: sent && !withdrawn ? 'awaiting_safety_review' : 'pending' }]),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, []),
      'GET /api/notifications': () => jsonResponse(200, notices()),
      'GET /api/notifications/unread-count': () => jsonResponse(200, { count: notices().length }),
    })
    render(<MemoryRouter><SessionProvider><NotificationBell /><Page /></SessionProvider></MemoryRouter>)
    await act(async () => { await Promise.resolve() })
    expect(within(screen.getByRole('region', { name: queue })).queryByRole('link', { name: event.eventName })).not.toBeInTheDocument()
    sent = true
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(within(screen.getByRole('region', { name: queue })).getByRole('link', { name: event.eventName })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Notifications, 1 unread' }))
    expect(screen.getByText('"Live workshop" is waiting for your safety review')).toBeInTheDocument()
    withdrawn = true
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(within(screen.getByRole('region', { name: queue })).queryByRole('link', { name: event.eventName })).not.toBeInTheDocument()
    expect(screen.getByText('"Live workshop" was withdrawn from safety review')).toBeInTheDocument()
    expect(screen.getByText('Reason: Attendance has changed')).toBeInTheDocument()
  })
})
