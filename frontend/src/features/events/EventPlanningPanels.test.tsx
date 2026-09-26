import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EventDetailsPage } from './EventDetailsPage'
import { OrganiserHomePage } from '../organiser/OrganiserHomePage'
import { NotificationBell } from '../notifications/NotificationBell'
import userEvent from '@testing-library/user-event'

const EVENT = 'ev-1'
const event = {
  eventId: EVENT, eventName: 'Town Hall', purpose: 'Update', description: null,
  startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 150,
  venueId: null, accessibilityNeeds: [], registrationNeeds: null, organisation: 'Acme Pte Ltd',
  venueRequirements: 'Theatre seating', equipmentRequirements: null, status: 'pending',
  coordinatorName: 'ec1', coordinatorEmail: null,
}
const history = [{ activityId: 'a1', type: 'approved', actorName: 'ec1', actorRole: 'ec', message: null,
  flaggedFields: [], fromStatus: 'pending', toStatus: 'approved', occurredAt: '2026-09-22T01:00:00Z' }]

function renderEvent(base: string) {
  return render(
    <MemoryRouter initialEntries={[`${base}/events/${EVENT}`]}>
      <SessionProvider>
        <Routes><Route path={`${base}/events/:eventId`} element={<EventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('EventDetailsPage planning panels (EC02/EC03)', () => {
  it('labels an approved, unconfirmed event as Planning and shows its history', async () => {
    seedSession('organiser')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, history),
    })
    renderEvent('/organiser')

    expect(await screen.findByText('Planning')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Request timeline' })).toHaveTextContent('approved the request')
  })

  it('offers the coordinator a venue request when none is active', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, []),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, history),
    })
    renderEvent('/coordinator')

    expect(await screen.findByRole('link', { name: 'Request a venue' }))
      .toHaveAttribute('href', `/coordinator/events/${EVENT}/venue-booking`)
  })

  it('shows the pending request instead of offering another', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [{ bookingId: 'b1', status: 'pending',
        venueId: 'v1', venueAddress: '1 Harbour Road', venueCapacity: 160, bookingNotes: null, suitabilityNote: null,
        rejectReason: null, submittedAt: null, submittedBy: 'ec1' }]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    expect(await screen.findByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText(/Pending venue review/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Request a venue' })).not.toBeInTheDocument()
  })
})

describe('Cancelling a pending venue booking request (EC03)', () => {
  it('cancels with an optional reason and then offers a new request', async () => {
    seedSession('coordinator')
    let cancelled = false
    const booking = { bookingId: 'b1', status: 'pending', venueId: 'v1', venueAddress: '1 Harbour Road', venueCapacity: 160,
      bookingNotes: null, suitabilityNote: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1' }
    const calls = stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [cancelled ? { ...booking, status: 'cancelled' } : booking]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
      [`POST /api/events/${EVENT}/venue-bookings/b1/cancel`]: () => {
        cancelled = true
        return jsonResponse(200, { ...booking, status: 'cancelled' })
      },
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    await user.click(await screen.findByRole('button', { name: 'Cancel this request' }))
    await user.type(screen.getByLabelText('Reason for Venue Staff (optional)'), 'Wrong venue')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByRole('link', { name: 'Request a venue' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'Wrong venue' })
  })
})

describe('OrganiserHomePage clarification row (EO26)', () => {
  it("highlights a request that needs clarification, shows the question, and links to Respond", async () => {
    seedSession('organiser')
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [{
        requestId: 'r1', requestType: 'C', eventId: null, eventName: 'Town Hall', purpose: 'x', description: null,
        startDatetime: null, endDatetime: null, expectedAttendance: 150, venueRequirements: 'x',
        equipmentRequirements: null, accessibilityNeeds: ['none'], registrationNeeds: null,
        status: 'clarification_required', createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-21T01:00:00Z',
        organisation: 'Acme Pte Ltd', coordinatorId: 'ec-1', rejectionReason: null,
      }]),
      'GET /api/event-requests/r1/timeline': () => jsonResponse(200, [{ activityId: 'a', type: 'clarification_requested',
        actorName: 'ec1', actorRole: 'ec', message: 'Is 150 final?', flaggedFields: [], fromStatus: 'pending',
        toStatus: 'clarification_required', occurredAt: '2026-09-21T01:00:00Z' }]),
    })
    render(<MemoryRouter initialEntries={['/organiser']}><SessionProvider><OrganiserHomePage /></SessionProvider></MemoryRouter>)

    expect(await screen.findByText('Is 150 final?')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Respond' })).toHaveAttribute('href', '/organiser/requests/r1/respond')
    expect(screen.getAllByText('Clarification required').length).toBeGreaterThan(0)
  })
})

describe('NotificationBell new notification types', () => {
  it('describes a clarification request and shows its message', async () => {
    seedSession('organiser')
    stubApi({
      'GET /api/notifications': () => jsonResponse(200, [{
        notificationId: 'n1', type: 'clarification_requested', eventRequestId: 'r1', eventId: null,
        eventName: 'Town Hall', newStatus: null, reason: null, coordinatorName: null, coordinatorEmail: null,
        isReassignment: null, occurredAt: new Date().toISOString(), read: false,
        linkPath: '/organiser/requests/r1/respond', message: 'Is 150 final?',
      }]),
      'GET /api/notifications/unread-count': () => jsonResponse(200, { count: 1 }),
    })
    const user = userEvent.setup()
    render(<MemoryRouter><SessionProvider><NotificationBell /></SessionProvider></MemoryRouter>)

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))

    expect(screen.getByText('Clarification needed on "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText('“Is 150 final?”')).toBeInTheDocument()
  })
})
