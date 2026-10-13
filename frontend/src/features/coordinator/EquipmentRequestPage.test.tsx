import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EquipmentRequestPage } from './EquipmentRequestPage'

const EVENT_ID = 'event-1'

const event = {
  eventId: EVENT_ID,
  eventName: 'Town Hall',
  purpose: 'All-hands update',
  description: null,
  startDatetime: '2027-03-10T01:00:00Z',
  endDatetime: '2027-03-10T04:00:00Z',
  expectedAttendance: 150,
  venueId: null,
  accessibilityNeeds: [],
  registrationNeeds: false,
  organisation: 'Acme',
  venueRequirements: 'Theatre layout',
  equipmentRequirements: 'Microphones for speakers',
  status: 'pending',
  coordinatorName: 'ec1',
  coordinatorEmail: null,
}

const catalogue = [
  { equipmentId: 'mic-1', equipmentName: 'Wireless microphone', totalQuantity: 8, serialised: true },
  { equipmentId: 'projector-1', equipmentName: 'Projector', totalQuantity: 3, serialised: true },
]

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/coordinator/events/${EVENT_ID}/equipment-request`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

function getBaseRoutes() {
  return {
    [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, event),
    'GET /api/equipment/catalogue': () => jsonResponse(200, catalogue),
    [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, []),
  }
}

describe('EquipmentRequestPage (EC07)', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('submits an event equipment request with item quantities and shows it awaiting review', async () => {
    seedSession('coordinator')
    const calls = stubApi({
      ...getBaseRoutes(),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: (body) => jsonResponse(201, {
        requestId: 'request-1',
        eventId: EVENT_ID,
        status: 'processing',
        technicalRequirement: (body as { technicalRequirement: string }).technicalRequirement,
        lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }],
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Add item' }))
    await user.selectOptions(screen.getByLabelText('Equipment'), 'mic-1')
    await user.clear(screen.getByLabelText('Quantity'))
    await user.type(screen.getByLabelText('Quantity'), '2')
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Awaiting Technical Support review')).toBeInTheDocument()
    expect(screen.getByText('2 × Wireless microphone')).toBeInTheDocument()
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      technicalRequirement: 'Microphones for speakers',
      items: [{ equipmentId: 'mic-1', quantity: 2 }],
    })
  })

  it('shows an existing active request instead of allowing a duplicate', async () => {
    seedSession('coordinator')
    stubApi({
      ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [{
        requestId: 'request-1',
        eventId: EVENT_ID,
        status: 'processing',
        technicalRequirement: 'Microphones',
        rejectReason: null,
        lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }],
      }]),
    })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Request already submitted' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit equipment request' })).not.toBeInTheDocument()
  })

  it('shows Technical Support rejection status and reason', async () => {
    seedSession('coordinator')
    stubApi({
      ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [{
        requestId: 'request-1',
        eventId: EVENT_ID,
        status: 'rejected',
        technicalRequirement: 'Microphones',
        rejectReason: 'Only one unit can be provided.',
        lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }],
      }]),
    })
    renderPage()

    expect(await screen.findByText(/Previous request was not approved/)).toBeInTheDocument()
    expect(screen.getByText('Reason: Only one unit can be provided.')).toBeInTheDocument()
  })
})
