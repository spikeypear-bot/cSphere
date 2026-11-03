// @stories EC07 EC-NEW3
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
    [`GET /api/events/${EVENT_ID}/equipment-availability`]: () => jsonResponse(200, []),
  }
}

describe('EquipmentRequestPage (EC07)', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC07 (happy): starts with EO selections, reveals other equipment only on edit and submits separate notes', async () => {
    seedSession('coordinator')
    const calls = stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'mic-1', quantity: 2 }],
        equipmentRequirements: 'Equipment items:\n2 × Wireless microphone\n\nAdditional notes:\nBring stands' }),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(201, { requestId: 'new', eventId: EVENT_ID,
        status: 'processing', technicalRequirement: 'Bring stands', lines: [] }),
    })
    renderPage()
    const user = userEvent.setup()
    expect(await screen.findByLabelText('Wireless microphone quantity')).toHaveValue(2)
    expect(screen.queryByLabelText('Projector')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Technical requirements or notes')).toHaveValue('Bring stands')
    await user.click(screen.getByRole('button', { name: 'Increase Wireless microphone quantity' }))
    await user.click(screen.getByRole('button', { name: 'Edit equipment' }))
    await user.click(screen.getByLabelText('Projector'))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))
    expect(calls.find(call => call.method === 'POST')?.body).toEqual({ technicalRequirement: 'Bring stands',
      items: [{ equipmentId: 'mic-1', quantity: 3 }, { equipmentId: 'projector-1', quantity: 1 }] })
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

    await user.click(await screen.findByRole('button', { name: 'Edit equipment' }))
    await user.click(screen.getByLabelText('Wireless microphone'))
    await user.clear(screen.getByLabelText('Wireless microphone quantity'))
    await user.type(screen.getByLabelText('Wireless microphone quantity'), '2')
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Awaiting Technical Support review')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Requested equipment' })).toHaveTextContent(/Wireless microphone.*× 2/)
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

  it('EC-NEW3 (happy): revises the active request; the approved one stays until Technical Support approve the revision', async () => {
    seedSession('coordinator')
    const calls = stubApi({
      ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [{
        requestId: 'request-1',
        eventId: EVENT_ID,
        status: 'approved',
        technicalRequirement: 'Two microphones',
        rejectReason: null,
        lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }],
      }]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request`]: (body) => jsonResponse(200, {
        requestId: 'request-2',
        eventId: EVENT_ID,
        status: 'processing',
        technicalRequirement: (body as { technicalRequirement: string }).technicalRequirement,
        lines: [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 1 }],
        revision: true,
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Revise request' }))
    expect(screen.getByDisplayValue('Two microphones')).toBeInTheDocument()
    await user.clear(screen.getByDisplayValue('Two microphones'))
    await user.type(screen.getByLabelText('Technical requirements or notes'), 'Fewer microphones, a projector now')
    await user.click(screen.getByRole('button', { name: 'Edit equipment' }))
    await user.click(screen.getByLabelText('Wireless microphone'))
    await user.click(screen.getByLabelText('Projector'))
    await user.clear(screen.getByLabelText('Projector quantity'))
    await user.type(screen.getByLabelText('Projector quantity'), '1')
    await user.click(screen.getByRole('button', { name: 'Submit revision' }))

    expect(await screen.findByText(/the current request and its reservations stay until they approve it/)).toBeInTheDocument()
    const [revision, kept] = screen.getAllByRole('list', { name: 'Requested equipment' })
    expect(revision).toHaveTextContent(/Projector.*× 1/)
    expect(kept).toHaveTextContent(/Wireless microphone.*× 2/)
    expect(screen.getByText(/replaces the approved request once Technical Support approve it/)).toBeInTheDocument()
    expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
      technicalRequirement: 'Fewer microphones, a projector now',
      items: [{ equipmentId: 'projector-1', quantity: 1 }],
    })
  })

  it('EC07 (negative): more than are free at the event\'s time cannot be sent until the quantity is lowered', async () => {
    seedSession('coordinator')
    const calls = stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'mic-1', quantity: 4 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-availability`]: () => jsonResponse(200, [{ equipmentId: 'mic-1', free: 3 }]),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: (body) => jsonResponse(201, { requestId: 'new', eventId: EVENT_ID,
        status: 'processing', technicalRequirement: '', lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone',
          quantity: (body as { items: { quantity: number }[] }).items[0].quantity }] }),
    })
    renderPage()
    const user = userEvent.setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('Wireless microphone: 4 selected, only 3 free')
    expect(screen.getByRole('button', { name: 'Submit equipment request' })).toBeDisabled()
    await user.clear(screen.getByLabelText('Wireless microphone quantity'))
    await user.type(screen.getByLabelText('Wireless microphone quantity'), '3')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Equipment request submitted to Technical Support for review.')).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1)
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({ items: [{ equipmentId: 'mic-1', quantity: 3 }] })
  })

  it('EC07 (boundary): exactly as many as are free can be sent', async () => {
    seedSession('coordinator')
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'mic-1', quantity: 3 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-availability`]: () => jsonResponse(200, [{ equipmentId: 'mic-1', free: 3 }]),
    })
    renderPage()

    expect(await screen.findByLabelText('Wireless microphone quantity')).toHaveValue(3)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit equipment request' })).toBeEnabled()
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

  describe('EC-NEW3 (D37): an equipment request that needs attention', () => {
    const flagged = {
      requestId: 'request-1',
      eventId: EVENT_ID,
      status: 'attention',
      technicalRequirement: 'Two microphones',
      rejectReason: null,
      attentionReason: "The event's equipment requirements text changed.",
      lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }],
    }

    it('EC-NEW3 (D37) (happy): shows that it needs attention, why, and both ways to resolve it', async () => {
      seedSession('coordinator')
      stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [flagged]) })
      renderPage()

      expect(await screen.findByText('Needs attention')).toBeInTheDocument()
      expect(screen.getByText("The event's equipment requirements text changed.")).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Revise request' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Mark still suitable' })).toBeInTheDocument()
    })

    it('EC-NEW3 (D37) (happy): marking it still suitable with a note sends the note and shows it approved', async () => {
      seedSession('coordinator')
      const calls = stubApi({
        ...getBaseRoutes(),
        [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [flagged]),
        [`PATCH /api/events/${EVENT_ID}/equipment-request/request-1/still-suitable`]: () =>
          jsonResponse(200, { ...flagged, status: 'approved', attentionReason: null }),
      })
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Mark still suitable' }))
      await user.type(screen.getByLabelText('Why is it still suitable?'), 'Wording only, same microphones')
      await user.click(screen.getByRole('button', { name: 'Confirm still suitable' }))

      expect(await screen.findByText('Approved')).toBeInTheDocument()
      expect(screen.queryByText('Needs attention')).not.toBeInTheDocument()
      expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({ note: 'Wording only, same microphones' })
    })

    it('EC-NEW3 (D37) (conflict): a flagged request is shown even when an approved one is listed before it', async () => {
      seedSession('coordinator')
      stubApi({
        ...getBaseRoutes(),
        [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [
          { ...flagged, requestId: 'request-0', status: 'approved', attentionReason: null },
          flagged,
        ]),
      })
      renderPage()

      expect(await screen.findByText('Needs attention')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Mark still suitable' })).toBeInTheDocument()
    })

    it('EC-NEW3 (D37) (negative): cannot confirm still suitable without a note', async () => {
      seedSession('coordinator')
      const calls = stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [flagged]) })
      const user = userEvent.setup()
      renderPage()

      await user.click(await screen.findByRole('button', { name: 'Mark still suitable' }))
      await user.type(screen.getByLabelText('Why is it still suitable?'), '   ')

      expect(screen.getByRole('button', { name: 'Confirm still suitable' })).toBeDisabled()
      expect(calls.some((call) => call.method === 'PATCH')).toBe(false)
    })
  })
})
