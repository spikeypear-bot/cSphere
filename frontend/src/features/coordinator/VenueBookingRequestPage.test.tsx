// @stories VS17 EC03 EC-C2 EC10 EC08  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { VenueOptionDto } from '../../types/venueBookingRequest'
import { VenueBookingRequestPage } from './VenueBookingRequestPage'

const EVENT = 'ev-1'

/** Opens a venue's details window from its card and adds it to the request. */
async function addVenue(name: RegExp) {
  await userEvent.click(await screen.findByRole('button', { name }))
  await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^(Add to request|Remove from request)$/ }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

it.each(['approved', 'pending', 'rejected', 'changed', 'cancelled'])('marks %s history labels for booking-only colour styling', async status => {
  seedSession('coordinator')
  stubApi(routes([{ bookingId: 'colour-booking', status, venueAddress: 'Colour test venue' }]))
  renderPage()
  const heading = await screen.findByRole('heading', { name: 'Earlier requests for this event' })
  const label = heading.closest('.venue-request__history')!.querySelector('[data-booking-status]')
  expect(label).toHaveAttribute('data-booking-status', status)
  expect(label).toHaveTextContent(new RegExp(status, 'i'))
  cleanup()
  vi.unstubAllGlobals()
})

it('submits one linked replacement for a rejected booking while preserving other active bookings', async () => {
  seedSession('coordinator')
  const source = {
    bookingId: 'old-booking', status: 'rejected', venueId: 'old-venue', venueAddress: 'Old Hall',
    venueCapacity: 200, bookingNotes: 'Original request notes', rejectReason: 'Venue unavailable',
    submittedAt: null, submittedBy: 'ec1',
  }
  const otherActive = {
    bookingId: 'other-booking', status: 'approved', venueId: 'other-venue', venueAddress: 'Other Hall',
    venueCapacity: 200, bookingNotes: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1',
  }
  const calls = stubApi(routes([source, otherActive], () => jsonResponse(201, {
    bookingId: 'new-booking', status: 'pending', replacesBookingId: 'old-booking',
  })))
  renderPage(`/coordinator/events/${EVENT}/venue-booking?replacesBookingId=old-booking`)

  expect(await screen.findByRole('heading', { name: 'Find a replacement venue' })).toBeInTheDocument()
  await addVenue(/1 Harbour Road/)
  await userEvent.click(screen.getByRole('button', { name: 'Request replacement 1 Harbour Road' }))

  expect(await screen.findByText('Event page')).toBeInTheDocument()
  expect(calls.find(call => call.method === 'POST')?.body).toMatchObject({
    venueId: 'v1',
    replacesBookingId: 'old-booking',
  })
})

it('EC10 (happy): offers same-venue rescheduling while preserving another active event venue', async () => {
  seedSession('coordinator')
  const source = {
    bookingId: 'old-booking', status: 'rejected', venueId: 'v1', venueAddress: '1 Harbour Road',
    venueCapacity: 160, bookingNotes: null, rejectReason: 'Venue unavailable', submittedAt: null, submittedBy: 'ec1',
  }
  const otherActive = {
    bookingId: 'other-booking', status: 'approved', venueId: 'v2', venueAddress: '2 Garden Lane',
    venueCapacity: 200, bookingNotes: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1',
  }
  const available = [...OPTIONS, option('v5', '5 Marina Place', 220, 'suitable')]
  stubApi({
    ...routes([source, otherActive]),
    [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, available),
  })
  renderPage(`/coordinator/events/${EVENT}/venue-booking?replacesBookingId=old-booking`)

  expect(await screen.findByRole('heading', { name: 'Find a replacement venue' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /1 Harbour Road/ })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /2 Garden Lane/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /5 Marina Place/ })).toBeInTheDocument()
})

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
    [`GET /api/events/${EVENT}/venue-assessments`]: () => jsonResponse(200, []),
    [`GET /api/events/${EVENT}/venue-reviews`]: () => jsonResponse(200, []),
    [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
    [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, OPTIONS),
    [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(200, bookings),
    ...(post ? {
      [`POST /api/events/${EVENT}/venue-bookings`]: post,
      [`POST /api/events/${EVENT}/venue-bookings/batch`]: post,
    } : {}),
  }
}

function renderPage(initialEntry = `/coordinator/events/${EVENT}/venue-booking`) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId/venue-booking" element={<VenueBookingRequestPage />} />
          <Route path="/coordinator/events/:eventId" element={<p>Event page</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('VenueBookingRequestPage: other events stay private (ECL-C7)', () => {
  it("ECL-C7 AC10, AC11 (negative): another event's booking shows as Booked by another event with its time, not its name", async () => {
    seedSession('coordinator')
    const busy = option('v1', '1 Harbour Road', 160, 'blocked', {
      conflicts: [{ description: 'Booked by another event', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z' }],
      reasons: ['Booked by another event, 10 Mar 2027, 9:00 AM – 12:00 PM (Singapore time).'],
    })
    stubApi({ ...routes(), [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, [busy]) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('checkbox', { name: /Show unavailable venues/ }))
    const card = (await screen.findAllByText('1 Harbour Road'))[0].closest('button') as HTMLElement
    expect(card).toHaveTextContent(/Booked by another event/)
    expect(card).not.toHaveTextContent(/Occupied:/)
    cleanup()
    vi.unstubAllGlobals()
  })
})

describe('VenueBookingRequestPage (EC03)', () => {
  it('submits independent windows and buffers for multiple venues in Singapore time', async () => {
    const first = { ...OPTIONS[0], venue: { ...OPTIONS[0].venue, setupMinutes: 30, turnaroundMinutes: 15 } }
    const second = { ...OPTIONS[1], verdict: 'suitable' as const, missingAccessibility: [], reasons: [] }
    const available = [first, second]
    const query = (start: string, end: string, setup: string, turnaround: string) =>
      'GET /api/events/' + EVENT + '/venue-options?' + new URLSearchParams({ setupDurationMinutes: setup,
        turnaroundDurationMinutes: turnaround, startDatetime: start, endDatetime: end })
    const calls = stubApi({ ...routes([], () => jsonResponse(201, [])),
      ['GET /api/events/' + EVENT + '/venue-options']: () => jsonResponse(200, available),
      [query('2027-03-10T02:00:00.000Z','2027-03-10T04:00:00.000Z','30','15')]: () => jsonResponse(200, available),
      [query('2027-03-10T06:00:00.000Z','2027-03-10T08:00:00.000Z','45','30')]: () => jsonResponse(200, available),
    })
    renderPage()
    await addVenue(/1 Harbour Road/)
    await addVenue(/2 Garden Lane/)
    const a = within(screen.getByRole('group', { name: 'Timings for 1 Harbour Road' }))
    const b = within(screen.getByRole('group', { name: 'Timings for 2 Garden Lane' }))
    expect(a.getByLabelText('Start')).toHaveValue('2027-03-10T09:00')
    expect(b.getByText(/Venue preparation before the event/)).toBeInTheDocument()
    expect(b.getByText(/Reset or clean the venue after the event/)).toBeInTheDocument()
    fireEvent.change(a.getByLabelText('Start'), { target: { value: '2027-03-10T10:00' } })
    fireEvent.change(b.getByLabelText('Start'), { target: { value: '2027-03-10T14:00' } })
    fireEvent.change(b.getByLabelText('End'), { target: { value: '2027-03-10T16:00' } })
    fireEvent.change(b.getByLabelText(/^Setup \(minutes\)/), { target: { value: '45' } })
    fireEvent.change(b.getByLabelText(/^Turnaround \(minutes\)/), { target: { value: '30' } })
    expect(screen.getByRole('button', { name: 'Request 2 venues' })).toBeDisabled()
    fireEvent.change(b.getByLabelText('Reason for different timings (required)'), { target: { value: 'Stage crew' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Request 2 venues' })).toBeEnabled())
    expect(a.getByLabelText(/^Setup \(minutes\)/)).toHaveValue(30)
    expect(b.getByText(/01:15 pm/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Request 2 venues' }))
    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find(c => c.method === 'POST')?.body).toMatchObject({ bookings: [
      { venueId: 'v1', startDatetime: '2027-03-10T02:00:00.000Z', endDatetime: '2027-03-10T04:00:00.000Z', setupDurationMinutes: 30, turnaroundDurationMinutes: 15 },
      { venueId: 'v2', startDatetime: '2027-03-10T06:00:00.000Z', endDatetime: '2027-03-10T08:00:00.000Z', setupDurationMinutes: 45, turnaroundDurationMinutes: 30, timingNote: 'Stage crew' },
    ] })
  })

  it('blocks submission when a changed booking window has an occupied-time conflict', async () => {
    const params = new URLSearchParams({ setupDurationMinutes: '0', turnaroundDurationMinutes: '0',
      startDatetime: '2027-03-10T02:00:00.000Z', endDatetime: '2027-03-10T04:00:00.000Z' })
    stubApi({ ...routes(), ['GET /api/events/' + EVENT + '/venue-options?' + params]: () => jsonResponse(200,
      [{ ...OPTIONS[0], verdict: 'blocked', reasons: ['Setup overlaps another booking.'] }]) })
    renderPage()
    await addVenue(/1 Harbour Road/)
    expect(screen.getByRole('button', { name: 'Request 1 Harbour Road' })).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Start'), { target: { value: '2027-03-10T10:00' } })
    expect(screen.getByRole('button', { name: 'Request 1 Harbour Road' })).toBeDisabled()
    expect(await screen.findByText('Setup overlaps another booking.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request 1 Harbour Road' })).toBeDisabled()
  })

  it('initialises saved venue defaults, rechecks overrides and requires a timing reason', async () => {
    seedSession('coordinator')
    const timed = { ...OPTIONS[0], venue: { ...OPTIONS[0].venue, setupMinutes: 30, turnaroundMinutes: 45 } }
    let submitted: unknown
    stubApi({ ...routes([], body => { submitted = body; return jsonResponse(201, {}) }),
      [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, [timed]),
      [`GET /api/events/${EVENT}/venue-options?setupDurationMinutes=60&turnaroundDurationMinutes=45&startDatetime=2027-03-10T01%3A00%3A00.000Z&endDatetime=2027-03-10T04%3A00%3A00.000Z`]: () => jsonResponse(200, [timed]),
    })
    renderPage()
    await addVenue(/1 Harbour Road/)
    expect(screen.getByLabelText(/^Setup \(minutes\)/)).toHaveValue(30)
    expect(screen.getByLabelText(/^Turnaround \(minutes\)/)).toHaveValue(45)
    await userEvent.clear(screen.getByLabelText(/^Setup \(minutes\)/))
    await userEvent.type(screen.getByLabelText(/^Setup \(minutes\)/), '60')
    expect(screen.getByRole('button', { name: 'Request 1 Harbour Road' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Reason for different timings (required)'), 'Organiser needs early crew access')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Request 1 Harbour Road' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))
    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(submitted).toMatchObject({ setupDurationMinutes: 60, turnaroundDurationMinutes: 45, timingNote: 'Organiser needs early crew access' })
  })
  it('allows a replacement for an affected confirmed event and sends the original booking id', async () => {
    seedSession('coordinator')
    let submitted: unknown
    stubApi({ ...routes([{ bookingId: 'original', status: 'approved', venueAddress: 'Closed Hall', requiresAlternative: true }], body => {
      submitted = body; return jsonResponse(201, {})
    }), [`GET /api/events/${EVENT}`]: () => jsonResponse(200, { ...event, status: 'confirmed' }) })
    renderPage()
    expect(await screen.findByText(/Alternative arrangements required for Closed Hall/)).toBeInTheDocument()
    await addVenue(/1 Harbour Road/)
    await userEvent.click(screen.getByRole('button', { name: 'Request replacement 1 Harbour Road' }))
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
    expect(await screen.findByText(/already has a replacement request/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Venues ranked for this event' })).not.toBeInTheDocument()
  })
  beforeEach(() => seedSession('coordinator'))
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
    await addVenue(/3 Small Street/)
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
    await addVenue(/4 Missing Facility Road/)
    expect(screen.getByRole('table')).toHaveTextContent('Missing: Projection')
    expect(screen.getByRole('button', { name: 'Request 4 Missing Facility Road' })).toBeDisabled()
  })

  it('shows coordinators the recorded unavailability reason and time window', async () => {
    const unavailable = {
      ...OPTIONS[0],
      verdict: 'blocked' as const,
      reasons: ['Recorded venue unavailability overlaps the event, setup or turnaround period.'],
      unavailablePeriods: [{
        description: 'Maintenance',
        affectedFrom: '2027-03-10T01:30:00Z',
        affectedUntil: '2027-03-10T02:30:00Z',
      }],
    }
    stubApi({
      ...routes(),
      [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(200, [unavailable]),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByLabelText('Show unavailable venues'))
    const venue = await screen.findByRole('button', { name: /1 Harbour Road/ })
    expect(venue).toHaveTextContent('Maintenance')
    expect(venue).toHaveTextContent('09:30')
    expect(venue).toHaveTextContent('10:30')
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

    await addVenue(/1 Harbour Road/)
    expect(screen.getByRole('table')).toHaveTextContent('Provided ✓')
    await user.type(screen.getByLabelText('Notes for Venue Staff (optional)'), 'Stage needed')
    await user.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))

    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      venueId: 'v1', bookingNotes: 'Stage needed', suitabilityNote: null,
      setupDurationMinutes: 0, turnaroundDurationMinutes: 0, timingNote: null,
    })
  })

  it('reviews and submits multiple venues for the same event', async () => {
    const calls = stubApi(routes([], () => jsonResponse(201, { status: 'pending' })))
    const user = userEvent.setup()
    renderPage()

    await addVenue(/1 Harbour Road/)
    await addVenue(/2 Garden Lane/)
    expect(screen.getByRole('heading', { name: 'Review and submit 2 venues' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Why is this venue still suitable? (required)'), 'Ramp hire arranged')
    expect(screen.getByRole('button', { name: 'Request 2 venues' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Request 2 venues' }))

    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({
      bookings: expect.arrayContaining([
        expect.objectContaining({ venueId: 'v1', startDatetime: expect.any(String), endDatetime: expect.any(String) }),
        expect.objectContaining({ venueId: 'v2', startDatetime: expect.any(String), endDatetime: expect.any(String) }),
      ]),
    })
  })

  it('a venue missing a requested accessibility feature cannot be requested without a justification', async () => {
    const calls = stubApi(routes([], () => jsonResponse(201, { bookingId: 'b2', status: 'pending' })))
    const user = userEvent.setup()
    renderPage()

    await addVenue(/2 Garden Lane/)
    const submit = screen.getByRole('button', { name: 'Request 2 Garden Lane' })
    expect(submit).toBeDisabled()
    expect(screen.getByRole('table')).toHaveTextContent('Not provided ✗')

    await user.type(screen.getByLabelText('Why is this venue still suitable? (required)'), 'Ramp hire arranged')
    await user.click(submit)

    expect(await screen.findByText('Event page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ suitabilityNote: 'Ramp hire arranged' })
  })

  it('allows adding another venue when the event already has an active request', async () => {
    stubApi(routes([{ bookingId: 'b1', status: 'pending', venueId: 'v1', venueAddress: '1 Harbour Road',
      venueCapacity: 160, bookingNotes: null, suitabilityNote: null, rejectReason: null, submittedAt: null, submittedBy: 'ec1' }]))
    renderPage()

    expect(await screen.findByRole('list', { name: 'Venues ranked for this event' })).toBeInTheDocument()
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

    await addVenue(/1 Harbour Road/)
    await user.click(screen.getByRole('button', { name: 'Request 1 Harbour Road' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('overlaps the event')
    expect(screen.queryByText('Event page')).not.toBeInTheDocument()
  })

  it('ECL-C7 AC2, AC18 (negative): tells a coordinator who is not assigned to the event that they cannot request a venue', async () => {
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT}/venue-options`]: () => jsonResponse(403, { message: 'Forbidden' }),
      [`GET /api/events/${EVENT}/venue-bookings`]: () => jsonResponse(403, { message: 'Forbidden' }),
    })
    renderPage()

    expect(await screen.findByRole('heading', { name: "This event isn't assigned to you" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to My events' })).toHaveAttribute('href', '/coordinator/my-events')
  })
})


describe('VS17 EC-C2 EC08 independent room requirements', () => {
  it('EC08 (happy): explicitly withdraws a rejected requirement with its reason', async () => {
    seedSession('coordinator')
    const booking = { bookingId: 'rejected-room', status: 'rejected', venueId: 'v1', venueAddress: '1 Harbour Road', requirementOutstanding: true }
    const calls = stubApi({ ...routes([booking]),
      [`POST /api/events/${EVENT}/venue-bookings/rejected-room/withdraw-requirement`]: () => jsonResponse(200, {...booking, requirementOutstanding: false}) })
    renderPage()
    await screen.findByText('This venue requirement still blocks event readiness.')
    await userEvent.type(screen.getByLabelText('Reason this venue is no longer required'), 'Breakout removed')
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw venue requirement' }))
    await waitFor(() => expect(screen.queryByText('This venue requirement still blocks event readiness.')).not.toBeInTheDocument())
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({reason: 'Breakout removed'})
  })
})

describe('EC03 venue details window', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC03 (happy): a venue card opens its details, and only "Add to request" adds it', async () => {
    const calls = stubApi(routes())
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /1 Harbour Road/ }))
    const details = screen.getByRole('dialog', { name: '1 Harbour Road' })
    expect(within(details).getByText('Operating hours')).toBeInTheDocument()
    // Looking is not choosing.
    expect(screen.queryByRole('list', { name: 'Selected venues' })).not.toBeInTheDocument()

    await userEvent.click(within(details).getByRole('button', { name: 'Add to request' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /^1 Harbour Road/ })).getByText('Added')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Selected venues' })).getByText('1 Harbour Road')).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
  })

  it('EC03 (happy): an added venue is removed from its details window', async () => {
    stubApi(routes())
    renderPage()

    await addVenue(/1 Harbour Road/)
    await userEvent.click(screen.getByRole('button', { name: /^1 Harbour Road/ }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove from request' }))
    expect(screen.queryByRole('list', { name: 'Selected venues' })).not.toBeInTheDocument()
  })
})
