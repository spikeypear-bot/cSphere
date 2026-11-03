// @stories EC07 EC-NEW3
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
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

  it('EC07 (happy): submits an event equipment request with item quantities and shows it awaiting review', async () => {
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

  it('EC07 (conflict): shows an existing active request instead of allowing a duplicate', async () => {
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

  it('EC07 (happy): shows Technical Support rejection status and reason', async () => {
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

function renderAt(query: string) {
  return render(
    <MemoryRouter initialEntries={[`/coordinator/events/${EVENT_ID}/equipment-request${query}`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const request = (requestId: string, status: string, extra: Record<string, unknown> = {}) => ({
  requestId, eventId: EVENT_ID, status, technicalRequirement: 'Two microphones', rejectReason: null,
  lines: [{ equipmentId: 'mic-1', equipmentName: 'Wireless microphone', quantity: 2 }], ...extra,
})

const damage = (extra: Record<string, unknown> = {}) => ({
  outcomeId: 'outcome-1', reportId: 'report-1', equipmentId: 'projector-1', equipmentName: 'Projector', serialNumber: null,
  damagedQuantity: 1, description: 'Cracked lens', periodStart: '2027-03-10T01:00:00Z', periodEnd: null,
  reportedAt: '2027-03-01T01:00:00Z', outcome: 'written_off', shortfall: 1, replacementSerialNumber: null,
  decidedAt: '2027-03-02T01:00:00Z', canRequestAdditional: true, ...extra,
})

describe('EC07 EC-NEW3 equipment request: loading, additional requests and failures', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC07 (failure): a failed load shows the server reason instead of the form', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(500, { message: 'Equipment service is down' }) })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Equipment service is down')
    expect(screen.queryByRole('button', { name: 'Submit equipment request' })).not.toBeInTheDocument()
  })

  it('EC07 (negative): a coordinator not assigned to the event is told so', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(403, { message: 'Forbidden' }) })
    renderPage()

    expect(await screen.findByRole('heading', { name: "This event isn't assigned to you" })).toBeInTheDocument()
  })

  it('EC07 (failure): when only the catalogue fails, the event still loads with the reason shown above it', async () => {
    stubApi({ ...getBaseRoutes(), 'GET /api/equipment/catalogue': () => jsonResponse(500, { message: 'Catalogue unavailable' }) })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Request equipment' })).toBeInTheDocument()
    expect(screen.getAllByRole('alert')[0]).toHaveTextContent('Catalogue unavailable')
  })

  it('EC07 (failure): if free stock cannot be read, nothing is blocked', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'mic-1', quantity: 9 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-availability`]: () => jsonResponse(500, {}) })
    renderPage()

    expect(await screen.findByLabelText('Wireless microphone quantity')).toHaveValue(9)
    expect(screen.queryByText(/Not enough equipment is free/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit equipment request' })).toBeEnabled()
  })

  it('EC07 (happy): items written in the event requirements are prefilled when the event has no saved items', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event,
      equipmentItems: [], equipmentRequirements: 'Equipment items:\n1 × Projector\n2 × Wireless microphone' }) })
    renderPage()

    expect(await screen.findByLabelText('Projector quantity')).toHaveValue(1)
    expect(screen.getByLabelText('Wireless microphone quantity')).toHaveValue(2)
    fireEvent.change(screen.getByLabelText('Projector quantity'), { target: { value: '2' } })
    expect(screen.getByLabelText('Projector quantity')).toHaveValue(2)
    expect(screen.getByLabelText('Wireless microphone quantity')).toHaveValue(2)
  })

  it('EC07 (negative): a shortage of an item missing from the catalogue names it by its id', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'ghost-1', quantity: 2 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-availability`]: () => jsonResponse(200, [{ equipmentId: 'ghost-1', free: 1 }]) })
    renderPage()

    expect(await screen.findByText(/ghost-1: 2 selected, only 1 free/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Submit equipment request' })).toBeDisabled()
  })

  it('EC07 (negative): outside Planning a new request cannot be submitted', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, status: 'confirmed' }) })
    renderPage()

    expect(await screen.findByText('Equipment requests can only be submitted while this event is in Planning.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit equipment request' })).not.toBeInTheDocument()
  })

  it('EC07 (failure): a refused submission shows the reason and keeps the form', async () => {
    const calls = stubApi({ ...getBaseRoutes(),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(422, { message: 'Add at least one item.' }) })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Add at least one item.')
    expect(screen.getByRole('button', { name: 'Submit equipment request' })).toBeEnabled()
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(1)
  })

  it('EC07 (failure): when the background refresh fails the page says so, and a submission still shows as awaiting review', async () => {
    let refreshes = 0
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => ++refreshes === 1 ? jsonResponse(200, event) : jsonResponse(500, { message: 'Refresh failed' }),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(201, request('request-1', 'processing')) })
    renderPage()

    expect(await screen.findByText('Could not refresh equipment requests. Refresh failed')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Submit equipment request' }))
    expect(await screen.findByText('Awaiting Technical Support review')).toBeInTheDocument()
  })

  it('EC07 (happy): a damage shortfall prefills what is missing and sends it as an additional request', async () => {
    const calls = stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('approved-1', 'approved')]),
      [`GET /api/events/${EVENT_ID}/equipment-damage`]: () => jsonResponse(200, [damage({ outcomeId: 'other' }), damage()]),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(201, request('extra-1', 'processing')) })
    renderAt('?shortfall=outcome-1')

    expect(await screen.findByText('Additional request for the shortfall: 1 × Projector reported damaged.')).toBeInTheDocument()
    expect(screen.getByLabelText('Projector quantity')).toHaveValue(1)
    expect(screen.getByLabelText('Technical requirements or notes')).toHaveValue('Replaces 1 × Projector reported damaged.')
    await userEvent.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Additional request for the shortfall submitted to Technical Support for review.')).toBeInTheDocument()
    expect(calls.find(call => call.method === 'POST')?.body).toEqual({ technicalRequirement: 'Replaces 1 × Projector reported damaged.',
      items: [{ equipmentId: 'projector-1', quantity: 1 }], shortfallOutcomeId: 'outcome-1' })
    expect(screen.queryByRole('button', { name: 'Revise request' })).not.toBeInTheDocument()
  })

  it('EC07 (negative): a shortfall that can no longer take a request says so', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-damage`]: () => jsonResponse(200, [damage({ canRequestAdditional: false })]) })
    renderAt('?shortfall=outcome-1')

    expect(await screen.findByText('This shortfall can no longer take an additional request.')).toBeInTheDocument()
  })

  it('EC-NEW3 (happy): asked to act on a change, the coordinator starts an empty additional request', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, status: 'confirmed', equipmentItems: [{ equipmentId: 'mic-1', quantity: 2 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('approved-1', 'approved')]),
      [`POST /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(201, request('extra-1', 'processing')) })
    renderAt('?additional=change')

    expect(await screen.findByText(/Technical Support asked you to act on an approved change/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Wireless microphone quantity')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Edit equipment' }))
    await userEvent.click(screen.getByLabelText('Projector'))
    await userEvent.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Additional request submitted to Technical Support for review.')).toBeInTheDocument()
  })

  it('EC07 (boundary): a rejected request with no note or reason shows only that it was not approved', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}/equipment-requests`]: () =>
      jsonResponse(200, [request('r1', 'rejected', { technicalRequirement: '' })]) })
    renderPage()

    expect(await screen.findByText('Previous request was not approved.')).toBeInTheDocument()
    expect(screen.queryByText(/Reason:/)).not.toBeInTheDocument()
  })
})

describe('EC-NEW3 revising and resolving an equipment request', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC-NEW3 (happy): the approved requirements are listed, revising starts from them, and Cancel leaves the request as it was', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => jsonResponse(200, { ...event, equipmentItems: [{ equipmentId: 'mic-1', quantity: 3 }, { equipmentId: 'ghost-1', quantity: 1 }] }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('approved-1', 'approved')]) })
    renderPage()

    const approved = (await screen.findByRole('heading', { name: 'Approved equipment requirements' })).parentElement!
    expect(approved).toHaveTextContent('3 × Wireless microphone')
    expect(approved).toHaveTextContent('1 × ghost-1')
    await userEvent.click(screen.getByRole('button', { name: 'Revise request' }))
    expect(screen.getByLabelText('Wireless microphone quantity')).toHaveValue(3)
    fireEvent.change(screen.getByLabelText('Wireless microphone quantity'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('Technical requirements or notes'), { target: { value: 'Changed' } })
    await userEvent.click(screen.getByRole('button', { name: 'Use approved equipment requirements' }))
    expect(screen.getByLabelText('Wireless microphone quantity')).toHaveValue(3)
    expect(screen.getByLabelText('Technical requirements or notes')).toHaveValue('Microphones for speakers')

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('heading', { name: 'Request already submitted' })).toBeInTheDocument()
  })

  it('EC-NEW3 (happy): revising a request still awaiting review replaces it at once', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('pending-1', 'processing')]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(200, request('pending-2', 'processing', { technicalRequirement: 'Three microphones' })) })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Revise request' }))
    expect(screen.getByLabelText('Wireless microphone quantity')).toHaveValue(2)
    await userEvent.click(screen.getByRole('button', { name: 'Submit revision' }))

    expect(await screen.findByText('Equipment request revised. Technical Support will review the new request.')).toBeInTheDocument()
    expect(screen.getByText('Three microphones')).toBeInTheDocument()
  })

  it('EC-NEW3 (failure): a refused revision shows the reason and keeps the form open', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('approved-1', 'approved')]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(409, { message: 'The request changed meanwhile.' }) })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Revise request' }))
    // The event has no saved items, so the approved requirements are its notes only.
    await userEvent.click(screen.getByRole('button', { name: 'Use approved equipment requirements' }))
    expect(screen.queryByLabelText('Wireless microphone quantity')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Technical requirements or notes')).toHaveValue('Microphones for speakers')
    await userEvent.click(screen.getByRole('button', { name: 'Submit revision' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The request changed meanwhile.')
    expect(screen.getByRole('button', { name: 'Submit revision' })).toBeEnabled()
  })

  it('EC-NEW3 (happy): a pending revision of a flagged request shows the flagged one is kept until approval', async () => {
    stubApi({ ...getBaseRoutes(), [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [
      request('flagged-1', 'attention', { attentionReason: 'Time changed.' }),
      request('revision-1', 'processing', { revision: true, technicalRequirement: 'Projector instead',
        lines: [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 1 }] }),
    ]) })
    renderPage()

    expect(await screen.findByText('Projector instead')).toBeInTheDocument()
    expect(screen.getByText(/replaces the approved request once Technical Support approve it/)).toBeInTheDocument()
    const [revision, kept] = screen.getAllByRole('list', { name: 'Requested equipment' })
    expect(revision).toHaveTextContent(/Projector.*× 1/)
    expect(kept).toHaveTextContent(/Wireless microphone.*× 2/)
  })

  it('EC-NEW3 (failure): a refused still-suitable note shows the reason; Cancel closes the note', async () => {
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('flagged-1', 'attention', { attentionReason: 'Time changed.' })]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request/flagged-1/still-suitable`]: () => jsonResponse(409, { message: 'Technical Support asked you to act.' }) })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Mark still suitable' }))
    await userEvent.type(screen.getByLabelText('Why is it still suitable?'), 'Same items')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm still suitable' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Technical Support asked you to act.')
    expect(screen.getByText('Needs attention')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByLabelText('Why is it still suitable?')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mark still suitable' })).toBeInTheDocument()
  })

  it('EC-NEW3 (happy): marking a request still suitable after a failed refresh leaves the other requests listed', async () => {
    let refreshes = 0
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => ++refreshes === 1 ? jsonResponse(200, event) : jsonResponse(500, { message: 'Refresh failed' }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [
        request('old-1', 'rejected', { rejectReason: 'Too many' }), request('flagged-1', 'attention', { attentionReason: 'Time changed.' })]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request/flagged-1/still-suitable`]: () => jsonResponse(200, request('flagged-1', 'approved')) })
    renderPage()

    await screen.findByText(/Could not refresh equipment requests/)
    await userEvent.click(screen.getByRole('button', { name: 'Mark still suitable' }))
    await userEvent.type(screen.getByLabelText('Why is it still suitable?'), 'Same items')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm still suitable' }))

    expect(await screen.findByText('Approved')).toBeInTheDocument()
    expect(screen.getByText('Reason: Too many')).toBeInTheDocument()
  })

  it('EC-NEW3 (happy): a revision after a failed refresh still marks the pending request replaced', async () => {
    let refreshes = 0
    stubApi({ ...getBaseRoutes(),
      [`GET /api/events/${EVENT_ID}`]: () => ++refreshes === 1 ? jsonResponse(200, event) : jsonResponse(500, { message: 'Refresh failed' }),
      [`GET /api/events/${EVENT_ID}/equipment-requests`]: () => jsonResponse(200, [request('pending-1', 'processing')]),
      [`PATCH /api/events/${EVENT_ID}/equipment-request`]: () => jsonResponse(200, request('pending-2', 'processing', { technicalRequirement: 'Three microphones' })) })
    renderPage()

    await screen.findByText(/Could not refresh equipment requests/)
    await userEvent.click(screen.getByRole('button', { name: 'Revise request' }))
    await userEvent.click(screen.getByRole('button', { name: 'Submit revision' }))

    expect(await screen.findByText('Three microphones')).toBeInTheDocument()
  })
})
