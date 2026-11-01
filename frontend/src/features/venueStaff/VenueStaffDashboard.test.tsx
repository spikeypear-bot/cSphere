// @stories VS02  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { VenueStaffHomePage } from './VenueStaffHomePage'

const REQUESTS = 'GET /api/venue-staff/booking-requests'

const booking = (n: number, over: object = {}) => ({
  bookingId: `b${n}`, status: 'pending',
  venue: { venueId: 'v1', venueAddress: 'Seminar Room', venueCapacity: 200, supportedLayouts: [], venueAccessibilities: [], venueFacilities: [] },
  event: { eventId: `e${n}`, eventName: n === 1 ? 'Workshop' : `Event ${n}`, startDatetime: '2027-03-10T01:00:00Z',
    endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 50, venueRequirements: null, accessibilityNeeds: [] },
  ...over,
})
const activeHold = { holdId: 'h1', bookingId: 'b9', status: 'active', serverTime: '2027-03-01T00:00:00Z',
  expiresAt: '2027-03-01T01:00:00Z', reservedStart: '2027-03-10T01:00:00Z', reservedEnd: '2027-03-10T04:00:00Z' }

function routes(requests: () => Response) {
  return stubApi({
    [REQUESTS]: requests,
    // The map explorer below the panel reads its own data.
    'GET /api/venues': () => jsonResponse(200, []),
    'GET /api/venue-staff/map/activity': () => jsonResponse(200, []),
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/venue-staff']}>
      <SessionProvider>
        <VenueStaffHomePage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = () => screen.findByRole('region', { name: 'Booking requests to review' })

describe('VS02 venue staff dashboard', () => {
  beforeEach(() => seedSession('venue-staff'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('VS02 (happy): booking requests to review are listed and open their booking', async () => {
    routes(() => jsonResponse(200, [booking(1), booking(2, { status: 'attention' })]))
    renderDashboard()

    const requests = await panel()
    expect(await within(requests).findByRole('link', { name: 'Workshop' })).toHaveAttribute('href', '/venue-staff/bookings/b1?from=booking-approvals')
    expect(within(requests).getByRole('link', { name: 'Event 2' })).toHaveAttribute('href', '/venue-staff/bookings/b2?from=booking-approvals')
    expect(within(requests).getByText('Requires attention')).toBeInTheDocument()
    expect(within(requests).getAllByText('Seminar Room')).toHaveLength(2)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, vs1$/)
    expect(screen.getByText('2 booking requests to review')).toBeInTheDocument()
  })

  it('VS02 (boundary): decided bookings and tentative holds are not listed for review', async () => {
    routes(() => jsonResponse(200, [booking(1), booking(2, { status: 'approved' }),
      booking(3, { status: 'rejected' }), booking(9, { holds: [activeHold] })]))
    renderDashboard()

    const requests = await panel()
    await within(requests).findByRole('link', { name: 'Workshop' })
    expect(within(requests).getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByText('1 booking request to review')).toBeInTheDocument()
  })

  it('VS02 (boundary): shows at most 5 rows and a View all link to booking approvals', async () => {
    routes(() => jsonResponse(200, [1, 2, 3, 4, 5, 6, 7].map((n) => booking(n))))
    renderDashboard()

    const requests = await panel()
    await within(requests).findByRole('link', { name: 'Workshop' })
    expect(within(requests).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all booking requests' })).toHaveAttribute('href', '/venue-staff/booking-approvals')
    expect(screen.getByText('7 booking requests to review')).toBeInTheDocument()
  })

  it('VS02 (boundary): the panel says so when there is nothing to review', async () => {
    routes(() => jsonResponse(200, []))
    renderDashboard()

    expect(await screen.findByText('No booking requests waiting for review.')).toBeInTheDocument()
  })

  it('VS02 (failure): the panel failing shows Try again while the venue map still loads', async () => {
    routes(() => jsonResponse(500, { message: 'Server error' }))
    renderDashboard()

    const requests = await panel()
    expect(await within(requests).findByRole('alert')).toBeInTheDocument()
    expect(within(requests).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Explore venues' })).toBeInTheDocument()
    expect(within(requests).getByRole('alert')).toHaveTextContent('Booking requests could not be loaded.')
  })

  it('VS02 (boundary): a booking whose tentative hold has lapsed is back to review, as on Booking approvals', async () => {
    // The server still says active, but its own clock puts the deadline now.
    const lapsed = { ...activeHold, bookingId: 'b3', expiresAt: activeHold.serverTime }
    routes(() => jsonResponse(200, [booking(3, { holds: [lapsed] })]))
    renderDashboard()

    expect(await within(await panel()).findByRole('link', { name: 'Event 3' })).toBeInTheDocument()
  })

  it('VS02 (boundary): a request with no event name or venue address still shows, saying so', async () => {
    routes(() => jsonResponse(200, [booking(4, {
      venue: { venueId: 'v1', venueAddress: '  ', venueCapacity: 200, supportedLayouts: [], venueAccessibilities: [], venueFacilities: [] },
      event: { eventId: 'e4', eventName: ' ', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
        expectedAttendance: 50, venueRequirements: null, accessibilityNeeds: [] },
    })]))
    renderDashboard()

    const requests = await panel()
    expect(await within(requests).findByRole('link', { name: 'Unnamed event' })).toHaveAttribute('href', '/venue-staff/bookings/b4?from=booking-approvals')
    expect(within(requests).getByText('Venue not specified')).toBeInTheDocument()
  })
})
