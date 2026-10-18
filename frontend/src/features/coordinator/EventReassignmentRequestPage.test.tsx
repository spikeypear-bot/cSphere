import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EventReassignmentRequestPage } from './EventReassignmentRequestPage'

const EVENT_ID = 'event-1'

const event = {
  eventId: EVENT_ID, eventName: 'Annual Summit', purpose: 'Company planning',
  description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
  expectedAttendance: 150, venueId: null, accessibilityNeeds: [], requiredFacilities: [],
  registrationNeeds: false, organisation: 'Acme', venueRequirements: 'Theatre',
  equipmentRequirements: null, status: 'pending', coordinatorName: 'ec1', coordinatorEmail: null,
}

const pending = {
  reassignmentRequestId: 'request-1', eventId: EVENT_ID, eventName: 'Annual Summit',
  organisation: 'Acme', eventStartDatetime: event.startDatetime, eventEndDatetime: event.endDatetime,
  requesterUserId: 'coordinator-1', requesterName: 'ec1', reason: 'I am no longer available.',
  status: 'pending', requestedAt: '2027-01-01T00:00:00Z', decidedAt: null, decisionReason: null,
  coordinatorUserId: 'coordinator-1', coordinatorName: 'ec1',
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/coordinator/events/${EVENT_ID}/reassignment-request`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId/reassignment-request" element={<EventReassignmentRequestPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EventReassignmentRequestPage (EC-C1)', () => {
  beforeEach(() => seedSession('coordinator', 'coordinator-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('submits a reason and displays the pending status', async () => {
    const calls = stubApi({
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT_ID}/reassignment-requests`]: () => jsonResponse(200, []),
      [`POST /api/events/${EVENT_ID}/reassignment-requests`]: () => jsonResponse(200, pending),
    })
    const user = userEvent.setup()
    renderPage()

    await user.type(await screen.findByLabelText('Reason for reassignment'), 'I am no longer available.')
    await user.click(screen.getByRole('button', { name: 'Send request' }))

    expect(await screen.findByRole('heading', { name: 'Request status: Pending' })).toBeInTheDocument()
    expect(screen.getByText(/sent to the Event Coordinator Lead/)).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ reason: 'I am no longer available.' })
  })

  it('shows an existing pending request without allowing duplicate submission', async () => {
    stubApi({
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT_ID}/reassignment-requests`]: () => jsonResponse(200, [pending]),
    })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Request status: Pending' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument()
    expect(screen.getByText(/continue planning this event while it is pending/)).toBeInTheDocument()
  })

  it('shows a decline reason and lets the still-assigned requester submit a new request', async () => {
    stubApi({
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT_ID}/reassignment-requests`]: () => jsonResponse(200, [{
        ...pending, status: 'declined', decisionReason: 'Please continue with the current coordinator.',
      }]),
    })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Request status: Declined' })).toBeInTheDocument()
    expect(screen.getByText('Please continue with the current coordinator.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send request' })).toBeInTheDocument()
  })

  it('does not offer the request form when the server denies the caller access', async () => {
    stubApi({
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, event),
      [`GET /api/events/${EVENT_ID}/reassignment-requests`]: () =>
        jsonResponse(403, { message: 'You do not have permission to perform this action.' }),
    })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission')
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument()
  })
})
