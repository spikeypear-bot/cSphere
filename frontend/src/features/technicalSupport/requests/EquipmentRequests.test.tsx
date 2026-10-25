// @stories TS09 TS01 TS02 TS06  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { toLocalInputValue } from '../reservations/reservationUtils'
import { EquipmentRequestDetailPage } from './EquipmentRequestDetailPage'
import { EquipmentRequestsPage } from './EquipmentRequestsPage'

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'
const iso = (local: string) => new Date(local).toISOString()
const period = `start=${encodeURIComponent(iso(toLocalInputValue(eventStart)))}&end=${encodeURIComponent(iso(toLocalInputValue(eventEnd)))}`

function request(id: string, extra: Record<string, unknown> = {}) {
  return {
    requestId: id, eventId: `event-${id}`, eventName: `Gala ${id}`, eventStart, eventEnd,
    technicalRequirement: 'Stage', status: 'processing', organisationName: 'Acme Pte Ltd',
    requestedBy: { userId: 'u-1', name: 'ec1' }, submittedAt: null, isShortfallRequest: false,
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
    // submittedAt is never stored yet, so nothing claims a submission time.
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

  it('TS09 TS01 TS02 TS06 (happy): one page checks availability, reserves the missing unit, then approves', async () => {
    const saved: unknown[] = []
    const reservation = {
      logId: 'log-1', eventId: 'event-r1', equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2,
      serialNumber: null, loanedFrom: iso(toLocalInputValue(eventStart)), loanedUntil: iso(toLocalInputValue(eventEnd)), eventName: 'Gala r1',
    }
    const calls = stubApi({
      'GET /api/equipment-requests?status=all': () => jsonResponse(200, [request('r1'), request('r2')]),
      'GET /api/equipment-requests/r1/lines': () => jsonResponse(200, [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }]),
      'GET /api/equipment/events/event-r1/reservations': () => jsonResponse(200, [...saved]),
      [`GET /api/equipment/requests/r1/availability?${period}`]: () => jsonResponse(200, [{
        equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: false, totalQuantity: 4,
        availableQuantity: saved.length ? 2 : 4,
      }]),
      [`GET /api/equipment/units?${period}`]: () => jsonResponse(200, []),
      'POST /api/equipment/reservations': () => {
        saved.push(reservation)
        return jsonResponse(201, reservation)
      },
      'PATCH /api/equipment-requests/r1/status': () => jsonResponse(200, {
        requestId: 'r1', eventId: 'event-r1', status: 'approved', technicalRequirement: 'Stage', rejectReason: null,
        lines: [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }],
      }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/requests/r1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Gala r1' })).toBeInTheDocument()
    expect(await screen.findByText('This equipment request can be fulfilled for the event period.')).toBeInTheDocument()
    expect(await screen.findByText('Projector: 0 of 2 reserved')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save approved decision' })).toBeDisabled()

    expect(await screen.findByText('Projector: 4 of 4 available')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Equipment'), 'eq-proj')
    await user.clear(screen.getByLabelText('Quantity'))
    await user.type(screen.getByLabelText('Quantity'), '2')
    await user.click(screen.getByRole('button', { name: 'Reserve' }))

    expect(await screen.findByText('Projector: 2 of 2 reserved')).toBeInTheDocument()
    expect(await screen.findByText('Projector: 2 of 4 available')).toBeInTheDocument()
    const approve = screen.getByRole('button', { name: 'Save approved decision' })
    expect(approve).toBeEnabled()
    await user.click(approve)

    expect(await screen.findByText('Status:')).toBeInTheDocument()
    expect(screen.getByText('approved')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reserve' })).not.toBeInTheDocument()
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({ eventId: 'event-r1', equipmentId: 'eq-proj', quantity: 2 })
    expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({ status: 'approved' })
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
