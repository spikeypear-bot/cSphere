// @stories VS17 EC03 EC-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EventDetailsPage } from './EventDetailsPage'
import { MyRequestsPage } from '../organiser/MyRequestsPage'
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

function renderEvent(base: string, state?: unknown) {
  const previousFetch = globalThis.fetch
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith('/venue-assessments') ? Promise.resolve(jsonResponse(200, [])) : previousFetch(input, init))
  return render(
    <MemoryRouter initialEntries={[{ pathname: `${base}/events/${EVENT}`, state }]}>
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
  it('EC02 (happy): an approved event that is not yet confirmed shows as Planning, with its history', async () => {
    seedSession('organiser')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, history),
    })
    renderEvent('/organiser')

    expect(await screen.findByText('Planning')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Request timeline' })).toHaveTextContent('approved the request')
  })

  it('EC03 (happy): the coordinator is offered a venue request when the event has none active', async () => {
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

  it('EC03 (negative): a pending venue request is shown and the coordinator is not offered another', async () => {
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

  it('EC03 VS17 (happy): every active venue booking for the event is shown', async () => {
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

  it.each(['rejected', 'cancelled'])('EC03 (happy): a %s venue booking offers a linked Rebook this venue action', async status => {
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

  it('EC03 (boundary): replacement attempts are shown without repeating their venue in the original request history', async () => {
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

  it('EC03 (happy): an active booking whose venue became unavailable offers a Find replacement venue action', async () => {
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
  it('EC03 (failure): when cancelling fails, the approved booking stays and the error is shown', async () => {
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

  it('EC03 (boundary): Cancel opens the confirmation for the chosen booking only', async () => {
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

  it.each(['pending', 'approved'])('EC03 (happy): cancelling a %s booking with an optional reason then offers a new venue request', async status => {
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

describe('EC02 EC03 EventDetailsPage venue card details', () => {
  const booking = { venueId: 'v1', venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: null,
    submittedAt: null, submittedBy: 'ec1', replacesBookingId: null }

  it('EC02 (happy): after approving, the coordinator is told the event is in Planning and the organiser was notified', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator', { justApproved: true })
    expect(await screen.findByText('Request approved. The event is now in Planning and the organiser has been notified.'))
      .toBeInTheDocument()
  })

  it('EC03 (happy): after requesting a venue, the coordinator is told which venue went to Venue Staff', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator', { bookingRequested: 'Main Hall' })
    expect(await screen.findByText('Booking request for Main Hall sent to Venue Staff for review.')).toBeInTheDocument()
  })

  it("EC03 (happy): a refused request shows Venue Staff's suggested venue and arrangement, and released bookings are listed", async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { ...booking, bookingId: 'b1', status: 'rejected', venueAddress: 'Old Hall', rejectReason: 'Too small',
          alternativeVenueAddress: 'Bay Room', alternativeArrangement: 'Split into two sessions' },
        { ...booking, bookingId: 'b2', status: 'changed', venueAddress: 'Released Room' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    const earlier = await screen.findByRole('list', { name: 'Earlier venue requests' })
    expect(earlier).toHaveTextContent('Reason: Too small')
    expect(earlier).toHaveTextContent('Suggested venue: Bay Room')
    expect(earlier).toHaveTextContent('Alternative arrangement: Split into two sessions')
    expect(screen.getByRole('heading', { name: 'Released bookings' })).toBeInTheDocument()
    expect(screen.getByText('Released Room: Changed')).toBeInTheDocument()
    expect(screen.getByText('No active venue bookings.')).toBeInTheDocument()
  })

  it('EC03 (negative): outside Planning and Confirmed, an earlier request cannot be rebooked and no new venue is offered', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, { ...event, status: 'safety_review' }),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { ...booking, bookingId: 'b1', status: 'cancelled', venueAddress: 'Old Hall' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    expect(await screen.findByRole('list', { name: 'Earlier venue requests' })).toHaveTextContent('Old Hall')
    expect(screen.queryByRole('link', { name: 'Rebook this venue' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Request a venue' })).not.toBeInTheDocument()
  })

  it('EC03 (boundary): a request shows only the times it has: a window alone, or a submission time alone', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { ...booking, bookingId: 'b1', status: 'pending', venueAddress: 'Window Hall',
          startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z' },
        { ...booking, bookingId: 'b2', status: 'approved', venueAddress: 'Submitted Hall', submittedAt: '2026-10-17T04:42:37Z' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderEvent('/coordinator')

    const items = within(await screen.findByRole('list', { name: 'Venue bookings' })).getAllByRole('listitem')
    const windowed = items.find((item) => item.textContent?.includes('Window Hall')) as HTMLElement
    const submitted = items.find((item) => item.textContent?.includes('Submitted Hall')) as HTMLElement
    expect(windowed).toHaveTextContent(new Date('2027-03-10T01:00:00Z').toLocaleString())
    expect(windowed).not.toHaveTextContent('Requested')
    expect(submitted).toHaveTextContent(`Requested ${new Date('2026-10-17T04:42:37Z').toLocaleString()}`)
    expect(submitted).not.toHaveTextContent('–')
  })

  it('EC03 (happy): Keep request closes the cancellation without sending anything', async () => {
    seedSession('coordinator')
    const calls = stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, [
        { ...booking, bookingId: 'b1', status: 'pending', venueAddress: 'Main Hall' },
      ]),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')
    await user.click(await screen.findByRole('button', { name: 'Cancel this request' }))
    await user.type(screen.getByLabelText('Reason for Venue Staff (optional)'), 'Changed my mind')
    await user.click(screen.getByRole('button', { name: 'Keep request' }))

    expect(screen.queryByLabelText('Reason for Venue Staff (optional)')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel this request' })).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Cancel this request' }))
    expect(screen.getByLabelText('Reason for Venue Staff (optional)')).toHaveValue('')
  })
})

describe('MyRequestsPage clarification row (EO26)', () => {
  it("EO26 (happy): a request that needs clarification is highlighted, shows the question, and links to Respond", async () => {
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
    render(<MemoryRouter initialEntries={['/organiser']}><SessionProvider><MyRequestsPage /></SessionProvider></MemoryRouter>)

    expect(await screen.findByText('Is 150 final?')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Respond' })).toHaveAttribute('href', '/organiser/requests/r1/respond')
    expect(screen.getAllByText('Clarification required').length).toBeGreaterThan(0)
  })
})

describe('NotificationBell new notification types', () => {
  it('EO26 (happy): a clarification request notification says what it is and shows the coordinator\'s message', async () => {
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
