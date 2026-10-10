import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { VenueOptionDto } from '../../types/venueBookingRequest'
import { VenueBookingRequestPage } from './VenueBookingRequestPage'

const EVENT = 'ev-1'

const event = {
  eventId: EVENT, eventName: 'Town Hall', purpose: 'Update', description: null,
  startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 150,
  venueId: null, accessibilityNeeds: ['step_free_access'], registrationNeeds: false, organisation: 'Acme Pte Ltd',
  venueRequirements: 'Theatre seating', equipmentRequirements: null, status: 'pending',
  coordinatorName: 'ec1', coordinatorEmail: null, requiredFacilities: ['stage'],
}

function option(id: string, address: string, capacity: number, verdict: VenueOptionDto['verdict'],
  extra: Partial<VenueOptionDto> = {}): VenueOptionDto {
  return {
    venue: {
      venueId: id, venueAddress: address, venueCapacity: capacity, supportedLayouts: ['theatre'],
      operatingInformation: '08:00-22:00', additionalInformation: null,
      venueAccessibilities: verdict === 'needs_justification' ? [] : ['step_free_access'], venueFacilities: ['stage'],
    },
    verdict, capacityOk: capacity >= 150, spareCapacity: capacity - 150,
    missingAccessibility: verdict === 'needs_justification' ? ['step_free_access'] : [], conflicts: [],
    reasons: [], ...extra,
  }
}

const OPTIONS = [
  option('v1', '1 Harbour Road', 160, 'suitable'),
  option('v2', '2 Garden Lane', 200, 'needs_justification', { reasons: ['Missing accessibility: step_free_access.'] }),
  option('v3', '3 Small Street', 100, 'blocked', { reasons: ['Capacity 100 is below the expected attendance of 150.'] }),
  option('v4', '4 Missing Facility Road', 200, 'blocked', {
    missingFacilities: ['projection'],
    reasons: ['Missing facilities: projection.'],
    venue: {
      venueId: 'v4', venueAddress: '4 Missing Facility Road', venueCapacity: 200,
      supportedLayouts: ['theatre'], operatingInformation: '08:00-22:00',
      additionalInformation: null, venueAccessibilities: ['step_free_access'], venueFacilities: ['stage'],
    },
  }),
]

function routes(bookings: unknown[] = [], post?: (body: unknown) => Response) {
  return {
    [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
    [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, OPTIONS),
    [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, bookings),
    ...(post ? { [`POST /api/events/${EVENT}/venue-bookings`]: post } : {}),
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/coordinator/events/${EVENT}/venue-booking`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId/venue-booking" element={<VenueBookingRequestPage />} />
          <Route path="/coordinator/events/:eventId" element={<p>Event page</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('VenueBookingRequestPage (EC03)', () => {
  it('allows a replacement for an affected confirmed event and sends the original booking id', async () => {
    seedSession('coordinator')
    let submitted: unknown
    stubApi({ ...routes([{ bookingId: 'original', status: 'approved', venueAddress: 'Closed Hall', requiresAlternative: true }], body => {
      submitted = body; return jsonResponse(201, {})
    }), [`GET /api/events/${EVENT}`]: () => jsonResponse(200, { ...event, status: 'confirmed' }) })
    renderPage()
    expect(await screen.findByText(/Alternative arrangements required for Closed Hall/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /1 Harbour Road/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))
    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(submitted).toMatchObject({ venueId: 'v1', replacesBookingId: 'original' })
  })
  it('blocks another replacement while one is already pending', async () => {
    seedSession('coordinator')
    stubApi(routes([
      { bookingId: 'replacement', status: 'pending', venueAddress: 'New Hall', replacesBookingId: 'original' },
      { bookingId: 'original', status: 'approved', venueAddress: 'Closed Hall', requiresAlternative: true },
    ]))
    renderPage()
    expect(await screen.findByText(/already has a pending venue review booking request/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Venues ranked for this event' })).not.toBeInTheDocument()
  })
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('lists bookable venues in the server ranking and hides unavailable ones until asked', async () => {
    stubApi(routes())
    const user = userEvent.setup()
    renderPage()

    const list = await screen.findByRole('list', { name: 'Venues ranked for this event' })
    const names = within(list).getAllByRole('button').map((b) => b.textContent)
    expect(names[0]).toContain('1 Harbour Road')
    expect(names[1]).toContain('2 Garden Lane')
    expect(within(list).queryByText('3 Small Street')).not.toBeInTheDocument()
    expect(screen.getByText('1 suitable · 1 need justification · 2 not available')).toBeInTheDocument()

    await user.click(await screen.findByLabelText('Show unavailable venues'))
    const blocked = screen.getByRole('button', { name: /3 Small Street/ })
    expect(blocked).not.toBeDisabled()
    expect(blocked).toHaveTextContent('Capacity 100 is below the expected attendance of 150.')
    await user.click(blocked)
    expect(screen.getByRole('table')).toHaveTextContent('100 ✗')
  })

  it('explains missing facilities and prevents submitting the venue', async () => {
    stubApi(routes())
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByLabelText('Show unavailable venues'))
    const blocked = await screen.findByRole('button', { name: /4 Missing Facility Road/ })
    expect(blocked).not.toBeDisabled()
    expect(blocked).toHaveTextContent('Missing facilities: projection.')
    await user.click(blocked)
    expect(screen.getByRole('table')).toHaveTextContent('Missing: Projection')
    expect(screen.getByRole('button', { name: 'Request 4 Missing Facility Road' })).toBeDisabled()
  })

  it('search narrows the list by address or details, and says so when nothing matches', async () => {
    stubApi(routes())
    const user = userEvent.setup()
    renderPage()

    const search = await screen.findByPlaceholderText('Search by address or details')
    await user.type(search, 'garden')
    const list = screen.getByRole('list', { name: 'Venues ranked for this event' })
    expect(within(list).getAllByRole('button').map((b) => b.textContent)).toEqual([
      expect.stringContaining('2 Garden Lane'),
    ])

    await user.clear(search)
    await user.type(search, 'nowhere')
    expect(screen.getByText(/No venues match/)).toBeInTheDocument()
  })

  it('shows capacity against attendance for each venue', async () => {
    stubApi(routes())
    renderPage()

    expect(await screen.findByText('150 of 160 seats · 10 spare')).toBeInTheDocument()
  })

  it('submits the selected suitable venue with notes and returns to the event', async () => {
    const calls = stubApi(routes([], () => jsonResponse(201, { bookingId: 'b1', status: 'pending' })))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /1 Harbour Road/ }))
    expect(screen.getByRole('table')).toHaveTextContent('Provided ✓')
    await user.type(screen.getByLabelText('Notes for Venue Staff (optional)'), 'Stage needed')
    await user.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))

    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      venueId: 'v1', bookingNotes: 'Stage needed', suitabilityNote: null,
    })
  })

  it('a venue missing a requested accessibility feature cannot be requested without a justification', async () => {
    const calls = stubApi(routes([], () => jsonResponse(201, { bookingId: 'b2', status: 'pending' })))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /2 Garden Lane/ }))
    const submit = screen.getByRole('button', { name: 'Request 2 Garden Lane' })
    expect(submit).toBeDisabled()
    expect(screen.getByRole('table')).toHaveTextContent('Not provided ✗')

    await user.type(screen.getByLabelText('Why is this venue still suitable? (required)'), 'Ramp hire arranged')
    await user.click(submit)

    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ suitabilityNote: 'Ramp hire arranged' })
  })

  it('an event that already has an active request cannot request another', async () => {
    stubApi(routes([{ bookingId: 'b1', status: 'pending', venueId: 'v1', venueAddress: '1 Harbour Road',
      venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1' }]))
    renderPage()

    expect(await screen.findByRole('status')).toHaveTextContent(
      'This event already has a pending venue review booking request for 1 Harbour Road.')
    expect(screen.queryByRole('list', { name: 'Venues ranked for this event' })).not.toBeInTheDocument()
  })

  it('shows rejection reason and alternative suggestions in coordinator booking history', async () => {
    stubApi(routes([{
      bookingId: 'b1', status: 'rejected', venueId: 'v1', venueAddress: '1 Harbour Road', venueCapacity: 160,
      bookingNotes: null, suitabilityNote: null, rejectReason: 'Unavailable on that date',
      alternativeVenueId: 'v2', alternativeVenueAddress: '2 Garden Lane',
      alternativeArrangement: 'Move the event to the afternoon', submittedAt: null, submittedBy: 'vs1',
    }]))
    renderPage()

    const heading = await screen.findByRole('heading', { name: 'Earlier requests for this event' })
    const history = heading.closest('.venue-request__history')!
    expect(history).toHaveTextContent('Unavailable on that date')
    expect(history).toHaveTextContent('2 Garden Lane')
    expect(history).toHaveTextContent('Move the event to the afternoon')
  })

  it('shows the server reason when a submission is refused and stays on the page', async () => {
    stubApi(routes([], () => jsonResponse(422, {
      message: "This venue already has a approved booking that overlaps the event's time (Board Meeting). Choose another venue.",
    })))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /1 Harbour Road/ }))
    await user.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('overlaps the event')
    expect(screen.queryByText('Event page')).not.toBeInTheDocument()
  })

  it('tells a coordinator who is not assigned to the event that they cannot request a venue', async () => {
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(403, { message: 'Forbidden' }),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(403, { message: 'Forbidden' }),
    })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Only the coordinator assigned to this event')
  })
})
