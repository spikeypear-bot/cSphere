// @stories SO-C1 SO-C3 SO-C4 SO-C5 SO-C6 EC08
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { ReadinessDto } from './readinessTypes'
import { SafetyReviewsPage } from './SafetyReviewsPage'
import { SafetyEventDetailsPage } from './SafetyEventDetailsPage'

const EVENT_ID = 'event-1'
const EVENT = {
  eventId: EVENT_ID, eventName: 'Town Hall', purpose: 'Update the team', description: 'Quarterly meeting',
  startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
  expectedAttendance: 150, venueId: 'venue-1', accessibilityNeeds: ['step_free_access'],
  requiredFacilities: ['stage'], registrationNeeds: true, organisation: 'Acme',
  venueRequirements: 'Theatre seating', equipmentRequirements: 'Microphones',
  // EC08: the coordinator has sent it for its safety check.
  status: 'awaiting_safety_review', coordinatorName: null, coordinatorEmail: null,
}
const READINESS: ReadinessDto = {
  eventId: EVENT_ID, approvalStatus: 'not_reviewed', approvalNote: null, reviewedBy: null, reviewedAt: null,
  flaggedItems: [],
  completedItems: 0, totalItems: 6,
  items: [{
    itemKey: 'venue_booking', label: 'Venue booking is approved and suitable',
    checked: false, note: '', checkedBy: null, checkedAt: null, updatedBy: null, updatedAt: null,
  }],
}
const VENUE = {
  venueId: 'venue-1', venueAddress: '1 Harbour Road', venueCapacity: 200,
  supportedLayouts: ['theatre'], venueAccessibilities: ['step_free_access'],
  venueFacilities: ['stage'], operatingInformation: 'Daily', additionalInformation: null,
  operatingHours: [],
}

afterEach(() => {
  cleanup()
  window.localStorage.clear()
})

describe('Safety Officer event views (SO-C1–SO-C4)', () => {
  it('shows confirmed events with their venue and the empty state when there are none', async () => {
    seedSession('safety-officer')
    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(200, []),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, [{
        eventId: EVENT_ID, eventName: 'Town Hall',
        startDatetime: EVENT.startDatetime, endDatetime: EVENT.endDatetime,
        venueAddress: '1 Harbour Road', status: 'confirmed',
      }]),
    })
    render(<MemoryRouter initialEntries={['/safety-officer/reviews']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/reviews" element={<SafetyReviewsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)

    expect(await screen.findByRole('heading', { name: 'Confirmed events' })).toBeInTheDocument()
    expect(screen.getByText('Town Hall')).toBeInTheDocument()
    expect(screen.getByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText('No events are waiting for your safety review.')).toBeInTheDocument()
  })

  it('lists only events sent for review as waiting; a withdrawn event (back in Planning) is not in the queue (EC08)', async () => {
    seedSession('safety-officer')
    const item = (eventId: string, eventName: string, status: string, code: string, label: string) => ({
      eventId, eventName, startDatetime: EVENT.startDatetime, endDatetime: EVENT.endDatetime, venueAddress: null, status,
      journeyStatus: { code, label, explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false },
    })
    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(200, [
        item('e1', 'Sent Gala', 'awaiting_safety_review', 'AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'),
        item('e2', 'Withdrawn Breakfast', 'pending', 'PLANNING', 'Planning'),
      ]),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, []),
    })
    render(<MemoryRouter initialEntries={['/safety-officer/reviews']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/reviews" element={<SafetyReviewsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)

    const queue = (await screen.findByRole('heading', { name: 'Waiting for your safety review' })).closest('section') as HTMLElement
    const planning = screen.getByRole('heading', { name: 'Still being planned' }).closest('section') as HTMLElement
    expect(within(queue).getByText('Sent Gala')).toBeInTheDocument()
    expect(within(queue).queryByText('Withdrawn Breakfast')).not.toBeInTheDocument()
    expect(within(planning).getByText('Withdrawn Breakfast')).toBeInTheDocument()
    // The same label every other role sees, not a console-only one.
    expect(within(queue).getByText('Awaiting Safety Review')).toBeInTheDocument()
  })

  it('SO-C1 (happy): each event card is one link that opens the event, named by the event', async () => {
    seedSession('safety-officer')
    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(200, []),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, [{
        eventId: EVENT_ID, eventName: 'Town Hall',
        startDatetime: EVENT.startDatetime, endDatetime: EVENT.endDatetime,
        venueAddress: '1 Harbour Road', status: 'confirmed',
      }]),
    })
    render(<MemoryRouter initialEntries={['/safety-officer/reviews']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/reviews" element={<SafetyReviewsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)

    const confirmed = (await screen.findByRole('heading', { name: 'Confirmed events' })).closest('section') as HTMLElement
    const card = await within(confirmed).findByRole('listitem')
    expect(within(card).getAllByRole('link')).toHaveLength(1)
    expect(within(card).getByRole('link', { name: 'Town Hall' })).toHaveAttribute('href', `/safety-officer/events/${EVENT_ID}`)
  })

  it('persists checklist changes, shows event logistics, and blocks approval while incomplete', async () => {
    seedSession('safety-officer')
    let checklist = READINESS
    const calls = stubApi({
      [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(200, EVENT),
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: () => jsonResponse(200, checklist),
      'GET /api/venues/venue-1': () => jsonResponse(200, VENUE),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(200, [{
        bookingId: 'booking-1', status: 'approved', venueId: 'venue-1', venueAddress: '1 Harbour Road',
        venueCapacity: 200, bookingNotes: null, suitabilityNote: null, rejectReason: null,
        submittedAt: null, submittedBy: 'vs1',
      }]),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [{
        requestId: 'equipment-request-1', status: 'approved', technicalRequirement: 'Wireless',
        rejectReason: null, lines: [{ equipmentId: 'mic-1', equipmentName: 'Microphone', quantity: 2 }],
      }]),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(200, [{
        logId: 'reservation-1', equipmentName: 'Microphone', quantity: 2,
        serialNumber: null, loanedFrom: '2027-03-10T00:00:00Z', loanedUntil: '2027-03-10T05:00:00Z',
      }]),
      [`PATCH /api/safety/events/${EVENT_ID}/readiness/items/venue_booking`]: (body) => {
        expect(body).toEqual({ checked: true, note: '' })
        checklist = { ...checklist, completedItems: 1, items: [{
          ...checklist.items[0], checked: true, checkedBy: 'so1', checkedAt: '2026-09-22T01:00:00Z',
          updatedBy: 'so1', updatedAt: '2026-09-22T01:00:00Z',
        }] }
        return jsonResponse(200, checklist)
      },
    })
    const user = userEvent.setup()
    render(<MemoryRouter initialEntries={[`/safety-officer/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/events/:eventId" element={<SafetyEventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)

    expect(await screen.findByText('1 Harbour Road')).toBeInTheDocument()
    expect(screen.getByText('Capacity')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Requested equipment' })).toHaveTextContent(/Microphone.*× 2/)
    const approve = await screen.findByRole('button', { name: 'Approve' })
    expect(approve).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: 'Venue booking is approved and suitable' }))

    expect(await screen.findByText('1 of 6 items complete')).toBeInTheDocument()
    expect(screen.getByText(/Checked by so1/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
    expect(calls.some((call) => call.method === 'PATCH')).toBe(true)
  })

  it('asks for a note before requesting changes, and sends the decision', async () => {
    seedSession('safety-officer')
    const complete = { ...READINESS, completedItems: 6, totalItems: 6 }
    const calls = stubApi({
      [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(200, EVENT),
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: () => jsonResponse(200, complete),
      [`GET /api/venues/venue-1`]: () => jsonResponse(404, { message: 'not needed here' }),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(200, []),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, []),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(200, []),
      [`POST /api/safety/events/${EVENT_ID}/approval`]: () =>
        jsonResponse(200, { ...complete, approvalStatus: 'changes_requested', approvalNote: 'Add a marshal' }),
    })
    const user = userEvent.setup()
    render(<MemoryRouter initialEntries={[`/safety-officer/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/events/:eventId" element={<SafetyEventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)

    const requestChanges = await screen.findByRole('button', { name: 'Request changes' })
    expect(requestChanges).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled()
    await user.type(screen.getByLabelText('Note for the Event Coordinator'), 'Add a marshal')
    expect(requestChanges).toBeDisabled()
    await user.click(within(screen.getByRole('group', { name: 'Checklist items this decision concerns' }))
      .getByRole('checkbox', { name: 'Raise: Venue booking is approved and suitable' }))
    await user.click(requestChanges)

    expect(await screen.findByText('Changes requested')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body)
      .toEqual({ decision: 'request_changes', note: 'Add a marshal', flaggedItems: ['venue_booking'] })
  })
})

describe('SO-C5 / SO-C6 Safety Officer decisions', () => {
  const ITEMS = [
    { itemKey: 'venue_booking', label: 'Venue booking is approved and suitable', checked: true, note: '',
      checkedBy: 'so1', checkedAt: '2026-09-22T01:00:00Z', updatedBy: 'so1', updatedAt: '2026-09-22T01:00:00Z' },
    { itemKey: 'safety_operations', label: 'Safety, emergency, and on-site operational arrangements are ready',
      checked: false, note: 'Exits blocked by the stage', checkedBy: null, checkedAt: null,
      updatedBy: 'so1', updatedAt: '2026-09-22T01:30:00Z' },
  ]
  const OPEN = { ...READINESS, completedItems: 1, totalItems: 2, items: ITEMS }

  function renderDetails() {
    render(<MemoryRouter initialEntries={[`/safety-officer/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/events/:eventId" element={<SafetyEventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)
  }

  function routes(event: () => Response, readiness: () => Response, approval?: () => Response) {
    return {
      [`GET /api/safety/events/${EVENT_ID}`]: event,
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: readiness,
      [`GET /api/venues/venue-1`]: () => jsonResponse(404, { message: 'not needed here' }),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(200, []),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, []),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(200, []),
      ...(approval ? { [`POST /api/safety/events/${EVENT_ID}/approval`]: approval } : {}),
    }
  }

  it('SO-C5 AC3, AC5, AC6 (negative): Reject stays disabled until a reason and a checklist item are given, then sends both', async () => {
    seedSession('safety-officer')
    const calls = stubApi(routes(() => jsonResponse(200, EVENT), () => jsonResponse(200, OPEN),
      () => jsonResponse(200, { ...OPEN, approvalStatus: 'rejected', approvalNote: 'Exits blocked',
        flaggedItems: ['safety_operations'], reviewedBy: 'so1', reviewedAt: '2026-09-22T02:00:00Z' })))
    const user = userEvent.setup()
    renderDetails()

    const reject = await screen.findByRole('button', { name: 'Reject' })
    const flags = screen.getByRole('group', { name: 'Checklist items this decision concerns' })
    await user.click(within(flags).getByRole('checkbox', { name: /Safety, emergency/ }))
    expect(reject).toBeDisabled()
    await user.type(screen.getByLabelText('Note for the Event Coordinator'), '   ')
    expect(reject).toBeDisabled()
    await user.type(screen.getByLabelText('Note for the Event Coordinator'), 'Exits blocked')
    expect(reject).toBeEnabled()
    await user.click(reject)

    expect(await screen.findByText('Rejected')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.body))
      .toEqual([{ decision: 'reject', note: '   Exits blocked', flaggedItems: ['safety_operations'] }])
  })

  it('SO-C5 AC5, SO-C6 AC5 (negative): approving sends no checklist items, even if some were ticked', async () => {
    seedSession('safety-officer')
    const complete = { ...OPEN, completedItems: 2, items: ITEMS.map((i) => ({ ...i, checked: true })) }
    const calls = stubApi(routes(() => jsonResponse(200, EVENT), () => jsonResponse(200, complete),
      () => jsonResponse(200, { ...complete, approvalStatus: 'approved' })))
    const user = userEvent.setup()
    renderDetails()

    await user.click(within(await screen.findByRole('group', { name: 'Checklist items this decision concerns' }))
      .getByRole('checkbox', { name: /Venue booking/ }))
    await user.click(screen.getByRole('button', { name: 'Approve' }))

    expect(await screen.findByText('Approved')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ decision: 'approve', note: '', flaggedItems: [] })
  })

  it('SO-C6 AC8 (happy): an event sent back for review shows the previous decision, its reason and the items it raised', async () => {
    seedSession('safety-officer')
    stubApi(routes(() => jsonResponse(200, EVENT), () => jsonResponse(200, {
      ...OPEN, approvalStatus: 'changes_requested', approvalNote: 'Add a second exit marshal',
      flaggedItems: ['safety_operations'], reviewedBy: 'so1', reviewedAt: '2026-09-22T02:00:00Z' })))
    renderDetails()

    expect(await screen.findByRole('heading', { name: 'Previous decision' })).toBeInTheDocument()
    expect(screen.getByText('Changes requested')).toBeInTheDocument()
    expect(screen.getByText('Add a second exit marshal')).toBeInTheDocument()
    expect(screen.getByText(/by so1 · 22 Sept 2026, 10:00 am \(Singapore time\)/)).toBeInTheDocument()
    const flaggedRow = screen.getByText("Raised by the Safety Officer's latest decision").closest('li')
    expect(flaggedRow).toHaveTextContent('Safety, emergency, and on-site operational arrangements are ready')
    // A new decision starts with nothing selected.
    const flags = screen.getByRole('group', { name: 'Checklist items this decision concerns' })
    expect(within(flags).getAllByRole('checkbox').filter((c) => (c as HTMLInputElement).checked)).toHaveLength(0)
  })

  it('SO-C5 AC8, SO-C6 AC9 (conflict): when the event was decided meanwhile, the refusal is shown with the event as it now is', async () => {
    seedSession('safety-officer')
    let decided = false
    stubApi(routes(
      () => jsonResponse(200, decided ? { ...EVENT, status: 'safety_changes_requested' } : EVENT),
      () => jsonResponse(200, decided ? { ...OPEN, approvalStatus: 'changes_requested', approvalNote: 'Add a marshal',
        flaggedItems: ['safety_operations'], reviewedBy: 'so2', reviewedAt: '2026-09-22T02:00:00Z' } : OPEN),
      () => {
        decided = true
        return jsonResponse(422, { message: 'A safety decision can only be made while the event is awaiting safety review.' })
      }))
    const user = userEvent.setup()
    renderDetails()

    await user.type(await screen.findByLabelText('Note for the Event Coordinator'), 'Exits blocked')
    await user.click(within(screen.getByRole('group', { name: 'Checklist items this decision concerns' }))
      .getByRole('checkbox', { name: /Safety, emergency/ }))
    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(await screen.findByText('A safety decision can only be made while the event is awaiting safety review.'))
      .toHaveAttribute('role', 'alert')
    expect(await screen.findByText(/by so2/)).toBeInTheDocument()
    expect(screen.getByText('Changes requested')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })
})

describe('SO-C1 SO-C3 SO-C4 Safety Officer pages — sparse data, failures and edges', () => {
  afterEach(() => vi.unstubAllGlobals())

  const unreadable = () => ({ ok: false, status: 502, text: async () => '<html>Bad gateway</html>' }) as Response
  const never = () => new Promise<Response>(() => {}) as unknown as Response

  function details(over: Record<string, (body: unknown) => Response> = {}) {
    seedSession('safety-officer')
    const calls = stubApi({
      [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(200, EVENT),
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: () => jsonResponse(200, READINESS),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(200, []),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, []),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(200, []),
      ...over,
    })
    render(<MemoryRouter initialEntries={[`/safety-officer/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/events/:eventId" element={<SafetyEventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)
    return calls
  }

  it('SO-C1 (boundary): sparse event, venue and equipment details read as not provided rather than blank', async () => {
    details({
      [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(200, { ...EVENT, status: 'archived', description: '',
        accessibilityNeeds: [], requiredFacilities: null, registrationNeeds: null }),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(200, [{
        bookingId: 'b1', status: 'attention', venueId: 'venue-9', venueAddress: null, venueCapacity: null,
        requirements: null, requiresAlternative: true,
      }]),
      'GET /api/venues/venue-9': () => jsonResponse(404, { message: 'Venue not found' }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [{
        requestId: 'r1', status: 'rejected', technicalRequirement: '', rejectReason: 'No stock', lines: [],
      }]),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(200, [{
        logId: 'l1', equipmentName: 'Projector', quantity: 1, serialNumber: 'SN-1',
        loanedFrom: '2027-03-10T00:00:00Z', loanedUntil: '2027-03-10T05:00:00Z',
      }]),
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: () => jsonResponse(500, { message: 'Down' }),
    })

    expect(await screen.findByRole('heading', { name: 'Venue — attention' })).toBeInTheDocument()
    expect(screen.getByText(/^archived · /)).toBeInTheDocument()
    const fact = (term: string) => screen.getAllByText(term).map((dt) => dt.nextElementSibling?.textContent)
    expect(fact('Description')).toEqual(['Not provided'])
    expect(fact('Accessibility needs')).toEqual(['None specified', 'None specified'])
    expect(fact('Registration')).toEqual(['Not finalised'])
    expect(fact('Capacity')).toEqual(['Not finalised'])
    expect(fact('Required facilities')).toEqual(['None specified'])
    expect(fact('Expected attendance in this room')).toEqual(['150'])
    expect(fact('Venue requirements')).toEqual(['Theatre seating'])
    expect(fact('Available facilities')).toEqual(['None listed'])
    expect(await screen.findByText('Could not load venue details. Venue not found')).toBeInTheDocument()
    expect(screen.getByText('This booking needs an alternative venue arrangement.')).toBeInTheDocument()
    expect(screen.getByText('No additional requirements.')).toBeInTheDocument()
    expect(screen.getByText('Reason: No stock')).toBeInTheDocument()
    expect(await screen.findByText('Reserved: Projector × 1 (SN-1)')).toBeInTheDocument()
    expect(await screen.findByText('Could not load readiness checklist. Down')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Safety approval' })).not.toBeInTheDocument()
  })

  it('SO-C1 (failure): bookings, equipment requests and reservations that cannot be loaded each say so', async () => {
    details({
      [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(200, { ...EVENT, status: 'safety_approved', registrationNeeds: false }),
      [`GET /api/safety/events/${EVENT_ID}/readiness`]: () => jsonResponse(200, { ...READINESS, approvalStatus: 'approved',
        reviewedBy: 'so1', reviewedAt: null, flaggedItems: ['retired_item'] }),
      [`GET /api/events/${EVENT_ID}/venue-bookings`]: () => jsonResponse(500, { message: 'Bookings down' }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(500, { message: 'Requests down' }),
      [`GET /api/equipment/events/${EVENT_ID}/reservations`]: () => jsonResponse(500, { message: 'Reservations down' }),
    })

    expect(await screen.findByText('Could not load booking details. Bookings down')).toBeInTheDocument()
    expect(await screen.findByText('Could not load equipment requests. Requests down')).toBeInTheDocument()
    expect(await screen.findByText('Could not load equipment reservations. Reservations down')).toBeInTheDocument()
    expect(screen.getByText('Registration').nextElementSibling).toHaveTextContent('Not required')
    expect(await screen.findByText('Approved. Waiting for the Event Coordinator to confirm the event.')).toBeInTheDocument()
    // Reviewed with no time recorded, and an item the checklist no longer has is still named.
    expect(screen.getByText('by so1', { exact: false })).toHaveTextContent(/^by so1$/)
    expect(within(screen.getByRole('list', { name: 'Checklist items concerned' })).getByText('retired_item')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('SO-C1 (failure): an event that cannot be loaded says so', async () => {
    details({ [`GET /api/safety/events/${EVENT_ID}`]: () => jsonResponse(404, { message: 'Event not found' }) })
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load this event. Event not found')
  })

  it('SO-C3 (boundary): while the checklist is still loading the page says so', async () => {
    details({ [`GET /api/safety/events/${EVENT_ID}/readiness`]: never })
    expect(await screen.findByText('Loading readiness checklist…')).toBeInTheDocument()
  })

  it('SO-C1 (boundary): opened without an event in the address, the page waits and asks the server for nothing', () => {
    seedSession('safety-officer')
    const calls = stubApi({})
    render(<MemoryRouter initialEntries={['/safety-officer/events']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/events" element={<SafetyEventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)
    expect(screen.getByRole('status')).toHaveTextContent('Loading event…')
    expect(calls).toEqual([])
  })

  it('SO-C3 (failure): a refused checklist save shows the server reason, an unreadable reply a plain message', async () => {
    let reply: () => Response = () => jsonResponse(409, { message: 'This checklist is locked.' })
    details({ [`PATCH /api/safety/events/${EVENT_ID}/readiness/items/venue_booking`]: () => reply() })
    const user = userEvent.setup()
    const box = await screen.findByRole('checkbox', { name: 'Venue booking is approved and suitable' })

    await user.click(box)
    expect(await screen.findByText('This checklist is locked.')).toHaveAttribute('role', 'alert')
    expect(screen.getByText('0 of 6 items complete')).toBeInTheDocument()

    reply = unreadable
    await user.click(box)
    expect(await screen.findByText('Could not save this checklist item.')).toHaveAttribute('role', 'alert')
  })

  it('SO-C4 (failure): an unreadable reply to a decision shows a plain message and keeps what was typed; unticking an item disables sending back again', async () => {
    details({ [`POST /api/safety/events/${EVENT_ID}/approval`]: unreadable })
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Note for the Event Coordinator'), 'Add a marshal')
    const raise = within(screen.getByRole('group', { name: 'Checklist items this decision concerns' }))
      .getByRole('checkbox', { name: 'Raise: Venue booking is approved and suitable' })
    await user.click(raise)
    expect(screen.getByRole('button', { name: 'Request changes' })).toBeEnabled()
    await user.click(raise)
    expect(screen.getByRole('button', { name: 'Request changes' })).toBeDisabled()
    await user.click(raise)
    await user.click(screen.getByRole('button', { name: 'Request changes' }))

    expect(await screen.findByText('Could not save the safety review.')).toHaveAttribute('role', 'alert')
    expect(screen.getByLabelText('Note for the Event Coordinator')).toHaveValue('Add a marshal')
  })

  it('SO-C1 (failure): the reviews list says when planning or confirmed events cannot be loaded, and shows an unlabelled status as given', async () => {
    seedSession('safety-officer')
    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(500, { message: 'Planning down' }),
      'GET /api/safety/events/confirmed': () => jsonResponse(500, { message: 'Confirmed down' }),
    })
    const view = render(<MemoryRouter initialEntries={['/safety-officer/reviews']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/reviews" element={<SafetyReviewsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)
    expect(await screen.findByText('Could not load planning events. Planning down')).toBeInTheDocument()
    expect(await screen.findByText('Could not load confirmed events. Confirmed down')).toBeInTheDocument()
    view.unmount()

    stubApi({
      'GET /api/safety/events/planning': () => jsonResponse(200, [{ eventId: 'e9', eventName: 'Odd Fair',
        startDatetime: EVENT.startDatetime, endDatetime: EVENT.endDatetime, venueAddress: null, status: 'on_hold' }]),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, []),
    })
    render(<MemoryRouter initialEntries={['/safety-officer/reviews']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer/reviews" element={<SafetyReviewsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>)
    expect(await screen.findByText('on_hold')).toBeInTheDocument()
  })
})
