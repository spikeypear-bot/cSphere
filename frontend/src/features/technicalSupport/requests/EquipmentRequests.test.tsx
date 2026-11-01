// @stories TS09 TS01 TS02 TS06  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { eventWindow, requestPageRoutes } from '../../../test/requestPageStubs'
import { EquipmentRequestDetailPage } from './EquipmentRequestDetailPage'
import { EquipmentRequestsPage } from './EquipmentRequestsPage'

// Entered times are Singapore time on every machine.
const sgtIso = (local: string) => new Date(`${local}:00+08:00`).toISOString()

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'

function request(id: string, extra: Record<string, unknown> = {}) {
  return {
    requestId: id, eventId: `event-${id}`, eventName: `Gala ${id}`, eventStart, eventEnd,
    technicalRequirement: 'Stage', status: 'processing', organisationName: 'Acme Pte Ltd',
    requestedBy: { userId: 'u-1', name: 'ec1' }, isShortfallRequest: false,
    items: [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }], eventStatus: 'PLANNING',
    ...extra,
  }
}

function Where() {
  const location = useLocation()
  return <output aria-label="location">{location.pathname}{location.search}</output>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/requests" element={<EquipmentRequestsPage />} />
          <Route path="/technical-support/requests/:requestId" element={<EquipmentRequestDetailPage />} />
        </Routes>
        <Where />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('TS09 equipment requests list and request page', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 (happy): lists requests awaiting review by default with event, dates, organisation, requester, items and a shortfall badge', async () => {
    const calls = stubApi({
      'GET /api/equipment-requests?status=processing': () => jsonResponse(200, [
        request('r1', { isShortfallRequest: true }),
        request('r2', { requestedBy: null, organisationName: 'Globex Holdings', items: [] }),
      ]),
    })
    renderAt('/technical-support/requests')

    const rows = within(await screen.findByRole('list', { name: 'Equipment requests' })).getAllByRole('listitem')
    expect(screen.getByLabelText('Status')).toHaveValue('processing')
    expect(within(rows[0]).getByRole('link', { name: 'Gala r1' })).toHaveAttribute('href', '/technical-support/requests/r1')
    expect(rows[0]).toHaveTextContent('Shortfall')
    expect(rows[0]).toHaveTextContent('Awaiting review')
    expect(rows[0]).toHaveTextContent('OrganisationAcme Pte Ltd')
    expect(rows[0]).toHaveTextContent('Requested byec1')
    expect(rows[0]).toHaveTextContent('Items2 × Projector')
    expect(rows[0]).toHaveTextContent('10 Mar 2027')
    expect(rows[1]).not.toHaveTextContent('Shortfall')
    expect(rows[1]).toHaveTextContent('Requested byNo coordinator assigned')
    expect(rows[1]).toHaveTextContent('OrganisationGlobex Holdings')
    // No submission time is recorded, so nothing claims one.
    expect(screen.queryByText(/Submitted/)).not.toBeInTheDocument()
    expect(calls.map((call) => call.url)).toEqual(['/api/equipment-requests?status=processing'])
  })

  it('TS09 (happy): the status filter reloads the list from the server and is kept in the URL', async () => {
    const calls = stubApi({
      'GET /api/equipment-requests?status=processing': () => jsonResponse(200, [request('r1')]),
      'GET /api/equipment-requests?status=approved': () => jsonResponse(200, [request('r2', { status: 'approved' })]),
      'GET /api/equipment-requests?status=all': () => jsonResponse(200, [
        request('r1'), request('r2', { status: 'approved' }), request('r3', { status: 'rejected' }),
      ]),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests')

    expect(await screen.findByRole('link', { name: 'Gala r1' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Status'), 'approved')
    expect(await screen.findByRole('link', { name: 'Gala r2' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Gala r1' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('location')).toHaveTextContent('/technical-support/requests?status=approved')

    await user.selectOptions(screen.getByLabelText('Status'), 'all')
    expect(await screen.findByRole('link', { name: 'Gala r3' })).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Equipment requests' })).getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText('Rejected', { selector: '.tech-badge' })).toBeInTheDocument()
    expect(calls.map((call) => call.url)).toEqual([
      '/api/equipment-requests?status=processing',
      '/api/equipment-requests?status=approved',
      '/api/equipment-requests?status=all',
    ])
  })

  it('TS09 (boundary): a status in the link opens that filter, and an unknown one falls back to awaiting review', async () => {
    const calls = stubApi({
      'GET /api/equipment-requests?status=rejected': () => jsonResponse(200, []),
      'GET /api/equipment-requests?status=processing': () => jsonResponse(200, []),
    })
    const first = renderAt('/technical-support/requests?status=rejected')
    expect(await screen.findByText('No equipment requests match this status.')).toBeInTheDocument()
    expect(screen.getByLabelText('Status')).toHaveValue('rejected')
    first.unmount()

    renderAt('/technical-support/requests?status=bogus')
    expect(await screen.findByText('No equipment requests are awaiting review.')).toBeInTheDocument()
    expect(screen.getByLabelText('Status')).toHaveValue('processing')
    expect(calls.map((call) => call.url)).toEqual([
      '/api/equipment-requests?status=rejected',
      '/api/equipment-requests?status=processing',
    ])
  })

  it('TS09 (failure): a list that cannot load says so and Try again reloads it', async () => {
    let tries = 0
    stubApi({
      'GET /api/equipment-requests?status=processing': () => (++tries === 1
        ? jsonResponse(500, { message: 'Server unavailable' })
        : jsonResponse(200, [request('r1')])),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests')

    expect(await screen.findByRole('alert')).toHaveTextContent('The equipment requests could not be loaded. Server unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Gala r1' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('TS09 TS01 TS02 TS06 (happy): one page checks availability, reserves every item in one batch with chosen units and a spare, then approves', async () => {
    const w = eventWindow(eventStart, eventEnd)
    const lines = [
      { equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 },
      { equipmentId: 'eq-mic', equipmentName: 'Microphone', quantity: 3 },
    ]
    const saved: Record<string, unknown>[] = []
    const reservation = (logId: string, equipmentId: string, equipmentName: string, quantity: number,
      serialNumber: string | null, spare: boolean) => ({
      logId, eventId: 'event-r1', equipmentId, equipmentName, quantity, serialNumber,
      loanedFrom: w.from, loanedUntil: w.to, eventName: 'Gala r1', spare,
    })
    const calls = stubApi({
      ...requestPageRoutes(request('r1'), {
        requests: () => [request('r1'), request('r2')],
        lines: () => lines,
        reservations: () => [...saved],
        availability: () => [
          { equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true, totalQuantity: 5, availableQuantity: saved.length ? 1 : 3 },
          { equipmentId: 'eq-mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 10, availableQuantity: saved.length ? 1 : 5 },
        ],
        units: () => ['P-1', 'P-2', 'P-3', 'P-4', 'P-5'].map((serialNumber) => ({
          equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber, status: serialNumber === 'P-1' ? 'Faulty' : 'Available',
        })),
        kindReservations: () => [reservation('other', 'eq-proj', 'Projector', 1, 'P-2', false)].map((r) => ({ ...r, eventId: 'event-other', eventName: 'Board Offsite' })),
      }),
      'POST /api/equipment/reservations/batch': () => {
        saved.push(
          reservation('l1', 'eq-proj', 'Projector', 1, 'P-4', false),
          reservation('l2', 'eq-proj', 'Projector', 1, 'P-5', false),
          reservation('l3', 'eq-mic', 'Microphone', 3, null, false),
          reservation('l4', 'eq-mic', 'Microphone', 1, null, true),
        )
        return jsonResponse(201, saved)
      },
      'PATCH /api/equipment-requests/r1/status': () => jsonResponse(200, {
        requestId: 'r1', eventId: 'event-r1', status: 'approved', technicalRequirement: 'Stage', rejectReason: null, lines,
      }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests/r1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Gala r1' })).toBeInTheDocument()
    expect(await screen.findByText('2 items still need reserving')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve request' })).toBeDisabled()
    // Free, working units are picked in serial order: P-1 is Faulty, P-2 is held by another event.
    expect(within(screen.getByRole('list', { name: 'Units to reserve: Projector' })).getAllByRole('listitem')
      .map((chip) => chip.textContent)).toEqual(['P-3auto', 'P-4auto'])
    await user.click(within(screen.getByRole('row', { name: /Projector/ })).getByRole('button', { name: /^Units/ }))
    expect(screen.getByText('Reserved: Board Offsite')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Choose units/ }))
    await user.click(screen.getByRole('checkbox', { name: 'P-3' }))
    expect(screen.getByText('Choose 1 more unit.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reserve all remaining/ })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'P-5' }))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Add a spare Microphone' }))
    expect(screen.getByText('1 spare')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reserve all remaining (6 units)' }))
    expect(await screen.findByText('Reserved 6 units for this event.')).toHaveFocus()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1)
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      eventId: 'event-r1', start: w.from, end: w.to,
      lines: [
        { equipmentId: 'eq-proj', quantity: 2, spare: 0, serialNumbers: ['P-4', 'P-5'] },
        { equipmentId: 'eq-mic', quantity: 3, spare: 1 },
      ],
    })

    expect(await screen.findByText('✓ Everything requested is reserved')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /Microphone/ })).getByText('+ 1 spare')).toBeInTheDocument()
    const approve = screen.getByRole('button', { name: 'Approve request' })
    expect(approve).toBeEnabled()
    await user.click(approve)

    expect(await screen.findByText('✓ This request was approved.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reserve all remaining/ })).not.toBeInTheDocument()
    expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({ status: 'approved' })
  })

  it('TS02 (failure): a refused batch marks the row the server names, says why, moves focus to the message and refreshes the figures', async () => {
    const calls = stubApi({
      ...requestPageRoutes(request('r1'), {
        requests: () => [request('r1')],
        lines: () => [
          { equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 },
          { equipmentId: 'eq-mic', equipmentName: 'Microphone', quantity: 1 },
        ],
        reservations: () => [],
        availability: () => [
          { equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: false, totalQuantity: 4, availableQuantity: 4 },
          { equipmentId: 'eq-mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 4, availableQuantity: 4 },
        ],
      }),
      'POST /api/equipment/reservations/batch': () => jsonResponse(409, {
        message: 'Only 1 of Projector available for that period', equipmentId: 'eq-proj',
      }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests/r1')

    await user.click(await screen.findByRole('button', { name: 'Reserve all remaining (3 units)' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Projector: Only 1 of Projector available for that period. Nothing was reserved')
    expect(alert).toHaveFocus()
    const row = screen.getByRole('row', { name: /Projector/ })
    expect(row).toHaveClass('fulfil-row--error')
    const input = screen.getByLabelText('Reserve now: Projector')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription(/Only 1 of Projector available/)
    expect(screen.getByRole('row', { name: /Microphone/ })).not.toHaveClass('fulfil-row--error')
    expect(screen.queryByText(/^Reserved \d+ unit/)).not.toBeInTheDocument()
    await waitFor(() => expect(calls.filter((call) => call.url.includes('/availability'))).toHaveLength(2))
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1)
  })

  it('TS02 (boundary): the loan window starts as the event’s times; a changed window reloads availability and is the one reserved, and an end before the start blocks reserving', async () => {
    const w = eventWindow(eventStart, eventEnd)
    const later = { start: '2027-03-10T08:00', end: '2027-03-10T13:00' }
    const laterQuery = `start=${encodeURIComponent(sgtIso(later.start))}&end=${encodeURIComponent(sgtIso(later.end))}`
    const calls = stubApi({
      ...requestPageRoutes(request('r1'), {
        requests: () => [request('r1')],
        lines: () => [{ equipmentId: 'eq-mic', equipmentName: 'Microphone', quantity: 2 }],
        reservations: () => [],
        availability: () => [{ equipmentId: 'eq-mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 4, availableQuantity: 4 }],
      }),
      [`GET /api/equipment/requests/r1/availability?${laterQuery}`]: () => jsonResponse(200, [
        { equipmentId: 'eq-mic', equipmentName: 'Microphone', serialised: false, totalQuantity: 4, availableQuantity: 1 },
      ]),
      [`GET /api/equipment/units?${laterQuery}`]: () => jsonResponse(200, []),
      'POST /api/equipment/reservations/batch': () => jsonResponse(201, []),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests/r1')

    expect(await screen.findByText(/· the event’s times/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Start')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change' }))
    fireEvent.change(screen.getByLabelText('End'), { target: { value: '2027-03-09T08:00' } })
    expect(screen.getByText('Choose an end time after the start time.')).toBeInTheDocument()
    expect(screen.getByLabelText('End')).toHaveAttribute('aria-invalid', 'true')

    fireEvent.change(screen.getByLabelText('Start'), { target: { value: later.start } })
    fireEvent.change(screen.getByLabelText('End'), { target: { value: later.end } })
    expect(await screen.findByText('Short: only 1 of the 2 still needed are free for this window.')).toBeInTheDocument()
    expect(screen.queryByText(/· the event’s times/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reserve all remaining (1 unit)' }))
    await waitFor(() => expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1))
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({
      start: sgtIso(later.start), end: sgtIso(later.end),
    })
    expect(w.from).not.toBe(sgtIso(later.start))
  })

  it('TS09 (negative): a request that is not in the list says it could not be found and loads nothing else', async () => {
    const calls = stubApi({
      'GET /api/equipment-requests?status=all': () => jsonResponse(200, [request('r1')]),
    })
    renderAt('/technical-support/requests/missing')

    expect(await screen.findByText(/This equipment request could not be found/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Gala r1' })).not.toBeInTheDocument()
    expect(calls).toHaveLength(1)
  })

  it('TS09 (cross-cutting): each request is one clickable card named after its event, its facts read as the description', async () => {
    stubApi({ 'GET /api/equipment-requests?status=processing': () => jsonResponse(200, [request('r1', { isShortfallRequest: true })]) })
    renderAt('/technical-support/requests')

    const row = within(await screen.findByRole('list', { name: 'Equipment requests' })).getAllByRole('listitem')[0]
    const links = within(row).getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAccessibleName('Gala r1')
    expect(links[0]).toHaveAccessibleDescription(/^Awaiting review Shortfall/)
    expect(links[0]).toHaveAccessibleDescription(/Requested by\s*ec1/)
    expect(within(links[0]).getByRole('heading', { level: 2, name: 'Gala r1' })).toBeInTheDocument()
  })
})
