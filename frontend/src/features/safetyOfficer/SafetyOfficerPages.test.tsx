// @stories SO-C1 SO-C3 SO-C4 SO-C5 SO-C6 EC08
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { ReadinessDto } from './readinessTypes'
import { SafetyOfficerHomePage } from './SafetyOfficerHomePage'
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
    render(<MemoryRouter initialEntries={['/safety-officer']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer" element={<SafetyOfficerHomePage />} /></Routes>
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
    render(<MemoryRouter initialEntries={['/safety-officer']}>
      <SessionProvider>
        <Routes><Route path="/safety-officer" element={<SafetyOfficerHomePage />} /></Routes>
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
    expect(screen.getByText('Microphone × 2')).toBeInTheDocument()
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
    await user.click(requestChanges)

    expect(await screen.findByText('Current approval: changes requested')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ decision: 'request_changes', note: 'Add a marshal' })
  })
})
