// @stories EC03 EC-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
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

  it('shows every active venue booking for the event', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { bookingId: 'b1', status: 'pending', venueId: 'v1', venueAddress: '1 Harbour Road',
          venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1' },
        { bookingId: 'b2', status: 'approved', venueId: 'v2', venueAddress: '2 Garden Lane',
          venueCapacity: 200, bookingNotes: null, suitabilityNote: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    expect(await screen.findByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText('2 Garden Lane')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Venue bookings' })).toHaveTextContent('Approved')
  })

  it.each(['rejected', 'cancelled'])('offers a linked rebooking action for a %s venue booking', async status => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [{
        bookingId: 'old-booking', status, venueId: 'v-old', venueAddress: 'Old Hall',
        venueCapacity: 160, bookingNotes: 'Keep these notes in history', suitabilityNote: null,
        rejectReason: status === 'rejected' ? 'Venue cannot support the event.' : null,
        submittedAt: '2026-10-17T04:42:37Z', submittedBy: 'ec1', replacesBookingId: null,
        startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
      }]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    expect(await screen.findByRole('link', { name: 'Rebook this venue' }))
      .toHaveAttribute('href', `/coordinator/events/${EVENT}/venue-booking?replacesBookingId=old-booking`)
    expect(screen.getByText(new RegExp(status, 'i'))).toBeInTheDocument()
    expect(screen.getByText((_, element) => !!element?.classList.contains('event-details__booking-meta')
      && !!element.textContent?.includes(new Date('2027-03-10T01:00:00Z').toLocaleString())
      && element.textContent.includes(`Requested ${new Date('2026-10-17T04:42:37Z').toLocaleString()}`)))
      .toBeInTheDocument()
  })

  it('shows replacement attempts without repeating their venue in the source history', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { bookingId: 'replacement', status: 'rejected', venueId: 'v-new', venueAddress: 'Marina Room',
          venueCapacity: 180, bookingNotes: null, suitabilityNote: null, rejectReason: 'Not available',
          submittedAt: null, submittedBy: 'ec1', replacesBookingId: 'old-booking' },
        { bookingId: 'old-booking', status: 'rejected', venueId: 'v-old', venueAddress: 'Old Harbour Hall',
          venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: 'Venue unavailable',
          submittedAt: null, submittedBy: 'ec1', replacesBookingId: null },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    const earlierRequests = await screen.findByRole('list', { name: 'Earlier venue requests' })
    expect(within(earlierRequests).getAllByText('Marina Room', { exact: true })).toHaveLength(1)
    expect(within(earlierRequests).getByText('Replacement attempt')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Replacement requests for Old Harbour Hall' }))
      .getByText('Rejected')).toBeInTheDocument()
    expect(within(earlierRequests).getAllByRole('link', { name: 'Rebook this venue' })
      .map(link => link.getAttribute('href')))
      .toContain(`/coordinator/events/${EVENT}/venue-booking?replacesBookingId=replacement`)
  })

  it('offers a replacement action for an unavailable active booking', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [{
        bookingId: 'affected-booking', status: 'approved', venueId: 'v-old', venueAddress: 'Affected Hall',
        venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: null,
        submittedAt: null, submittedBy: 'ec1', requiresAlternative: true,
      }]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    expect(await screen.findByRole('link', { name: 'Find replacement venue' }))
      .toHaveAttribute('href', `/coordinator/events/${EVENT}/venue-booking?replacesBookingId=affected-booking`)
  })
})

describe('Cancelling a pending venue booking request (EC03)', () => {
  it('retains the approved booking and displays a cancellation failure', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { bookingId: 'b1', status: 'approved', venueAddress: 'Main Hall' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
      [`POST /api/events/${EVENT}/venue-bookings/b1/cancel`]: () => jsonResponse(409, { message: 'Resolve the pending replacement first.' }),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')
    await user.click(await screen.findByRole('button', { name: 'Cancel this booking' }))
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))
    expect(await screen.findByText('Resolve the pending replacement first.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Venue bookings' })).toHaveTextContent('Approved')
  })

  it('opens cancellation for only the selected booking', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { bookingId: 'b1', status: 'approved', venueAddress: 'Main Hall' },
        { bookingId: 'b2', status: 'approved', venueAddress: 'Other Hall' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')
    await user.click((await screen.findAllByRole('button', { name: 'Cancel this booking' }))[0])
    expect(screen.getAllByRole('button', { name: 'Confirm cancellation' })).toHaveLength(1)
    expect(screen.getByText('Other Hall')).toBeInTheDocument()
  })

  it.each(['pending', 'approved'])('cancels a %s booking with an optional reason and then offers a new request', async status => {
    seedSession('coordinator')
    let cancelled = false
    const booking = { bookingId: 'b1', status, venueId: 'v1', venueAddress: '1 Harbour Road', venueCapacity: 160,
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

    await user.click(await screen.findByRole('button', { name: status === 'approved' ? 'Cancel this booking' : 'Cancel this request' }))
    await user.type(screen.getByLabelText('Reason for Venue Staff (optional)'), 'Wrong venue')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByRole('link', { name: 'Request a venue' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Earlier venue requests' })).toBeInTheDocument()
    expect(screen.getByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText('Cancelled')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Rebook this venue' })).toHaveAttribute(
      'href', `/coordinator/events/${EVENT}/venue-booking?replacesBookingId=b1`,
    )
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
