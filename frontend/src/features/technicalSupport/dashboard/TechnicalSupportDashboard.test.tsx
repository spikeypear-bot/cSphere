// @stories TS09 TS01 TS03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { TechnicalSupportDashboardPage } from './TechnicalSupportDashboardPage'

const REQUESTS = 'GET /api/equipment-requests?status=processing'
const PERIODS = 'GET /api/equipment/status-periods?current=true'
const REPORTS = 'GET /api/equipment-damage-reports'
// Wednesday 10 March 2027, 3:30 pm in Singapore (event times are shown in
// Singapore time). "Today → tomorrow" is that local day.
const NOW = new Date('2027-03-10T07:30:00Z')
const CATALOGUE = `GET /api/equipment/catalogue?from=${encodeURIComponent(new Date(2027, 2, 10).toISOString())}&to=${encodeURIComponent(new Date(2027, 2, 11).toISOString())}`

function request(n: number, extra: Record<string, unknown> = {}) {
  return {
    requestId: `req-${n}`, eventId: `event-${n}`, eventName: `Event ${n}`,
    eventStart: '2027-03-12T01:00:00Z', eventEnd: '2027-03-12T04:00:00Z',
    technicalRequirement: '', status: 'processing', organisationName: 'Acme Pte Ltd',
    requestedBy: { userId: 'u-1', name: 'ec1' }, submittedAt: null, isShortfallRequest: false,
    items: [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }], eventStatus: 'PLANNING',
    ...extra,
  }
}

function period(id: string, start: string, status = 'Faulty', end: string | null = null) {
  return { id, equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: `PJ-${id}`, status, start, end }
}

function report(id: string, periodStart: string, status: 'OPEN' | 'CLOSED' = 'OPEN') {
  return {
    reportId: id, equipmentId: 'eq-hdmi', equipmentName: 'HDMI Cable', serialised: false, serialNumber: null,
    quantity: 3, description: 'Bent', periodStart, periodEnd: '2027-03-20T00:00:00Z', markFaulty: false, faultyMarked: false,
    status, reportedBy: 't-1', reportedByName: 'ts1', reportedAt: periodStart, closedAt: null, canClose: false, affectedEvents: [],
  }
}

const kind = (name: string, total: number, reserved: number, out: number, available: number) => ({
  equipmentId: `eq-${name}`, equipmentName: name, totalQuantity: total, serialised: false,
  reservedQuantity: reserved, outOfActionQuantity: out, availableQuantity: available,
})

const line = (equipmentName: string, quantity: number) => ({ equipmentId: `eq-${equipmentName}`, equipmentName, quantity })

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/technical-support']}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support" element={<TechnicalSupportDashboardPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const rowsOf = async (list: string) => within(await screen.findByRole('list', { name: list })).getAllByRole('listitem')

describe('TS09 Technical Support front page', () => {
  beforeEach(() => {
    seedSession('technical-support')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 (happy): greets the signed-in user by time of day and sums up the requests to handle and equipment out of action', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6].map((n) => request(n))),
      [PERIODS]: () => jsonResponse(200, [period('1', '2027-03-08T00:00:00Z')]),
      [REPORTS]: () => jsonResponse(200, [report('rep-open', '2027-03-09T00:00:00Z'), report('rep-closed', '2027-03-09T00:00:00Z', 'CLOSED')]),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    expect(screen.getByRole('heading', { level: 1, name: 'Good afternoon, tech1' })).toBeInTheDocument()
    expect(screen.getByText('Checking what needs your attention…')).toBeInTheDocument()
    // Every request awaiting review counts, not only the five shown; closed reports do not.
    expect(await screen.findByText('6 requests to handle · 2 items out of action')).toBeInTheDocument()
  })

  it('TS09 (boundary): the summary uses the singular for one, and leaves out a part that could not be loaded', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, [request(1)]),
      [PERIODS]: () => jsonResponse(500, { message: 'Blocks down' }),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    expect(await screen.findByText('1 request to handle')).toBeInTheDocument()
    expect(screen.queryByText(/out of action/)).not.toBeInTheDocument()
    expect(within(panel('Faulty equipment')).getByRole('alert')).toHaveTextContent('Blocks down')
  })

  it('TS09 TS01 (happy): Requests to handle shows the five most urgent as whole-row links with when, requester and items', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, [
        request(1, { isShortfallRequest: true, eventStart: '2027-03-10T09:00:00Z', eventEnd: '2027-03-10T12:00:00Z' }),
        request(2, { requestedBy: null, items: [], eventStart: '2027-03-11T01:00:00Z', eventEnd: '2027-03-11T04:00:00Z' }),
        request(3, { eventStart: '2027-03-10T10:00:00Z', eventEnd: '2027-03-10T17:00:00Z' }),
        request(4, { items: [line('Projector', 2), line('Webcam', 1), line('Laptop', 3), line('Tripod', 1), line('Ring Light', 2)] }),
        request(5, { items: [line('Projector', 2), line('Webcam', 1), line('Laptop', 3)] }),
        request(6),
      ]),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    const rows = await rowsOf('Requests to handle')
    // Top five in the server's order (soonest first), one link per row named after the event.
    expect(rows.map((row) => within(row).getByRole('link').getAttribute('href'))).toEqual(
      [1, 2, 3, 4, 5].map((n) => `/technical-support/requests/req-${n}`),
    )
    expect(within(rows[0]).getByRole('link', { name: 'Event 1' })).toHaveAccessibleDescription(/Shortfall/)
    expect(rows[0]).toHaveTextContent('Today, 5:00 pm – 8:00 pm')
    expect(rows[0]).toHaveTextContent('ec1 · 2 × Projector')
    expect(within(rows[0]).getByText('Shortfall')).toHaveClass('tech-badge')
    expect(rows[1]).toHaveTextContent('Tomorrow, 9:00 am – 12:00 pm')
    expect(rows[1]).toHaveTextContent('No coordinator assigned · No itemised equipment')
    expect(rows[1]).not.toHaveTextContent('Shortfall')
    expect(rows[2]).toHaveTextContent('Today, 6:00 pm – Tomorrow, 1:00 am')
    expect(rows[3]).toHaveTextContent('Fri 12 Mar, 9:00 am – 12:00 pm')
    expect(rows[3]).toHaveTextContent('2 × Projector, 1 × Webcam, 3 × Laptop +2 more')
    expect(rows[4]).toHaveTextContent('2 × Projector, 1 × Webcam, 3 × Laptop')
    expect(rows[4]).not.toHaveTextContent('more')
    expect(screen.queryByRole('link', { name: 'Event 6' })).not.toBeInTheDocument()
    expect(within(panel('Requests to handle')).getByRole('link', { name: 'View all equipment requests' }))
      .toHaveAttribute('href', '/technical-support/requests')
  })

  it('TS09 TS03 (happy): Faulty equipment merges current blocks with open reports, newest first, each row opening its page', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, [
        period('1', '2027-03-08T00:00:00Z'),
        period('2', '2027-03-01T00:00:00Z', 'Unavailable', '2027-03-15T01:00:00Z'),
        period('3', '2027-02-01T00:00:00Z'),
        period('4', '2027-01-01T00:00:00Z'),
      ]),
      [REPORTS]: () => jsonResponse(200, [
        report('rep-new', '2027-03-09T00:00:00Z'),
        report('rep-closed', '2027-03-09T12:00:00Z', 'CLOSED'),
        report('rep-old', '2027-01-15T00:00:00Z'),
      ]),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    const rows = await rowsOf('Faulty equipment')
    expect(rows.map((row) => within(row).getByRole('link').getAttribute('href'))).toEqual([
      '/technical-support/faulty/reports/rep-new',
      '/technical-support/inventory/eq-proj',
      '/technical-support/inventory/eq-proj',
      '/technical-support/inventory/eq-proj',
      '/technical-support/faulty/reports/rep-old',
    ])
    expect(within(rows[0]).getByRole('link', { name: 'HDMI Cable' })).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('Damage report')
    expect(rows[0]).toHaveTextContent('3 units · Until Sat 20 Mar')
    expect(within(rows[0]).getByRole('img', { name: 'HDMI Cable' })).toHaveAttribute('src')
    expect(within(rows[1]).getByRole('link', { name: 'Projector' })).toHaveAccessibleDescription(/Faulty/)
    expect(rows[1]).toHaveTextContent('PJ-1 · No end date')
    expect(rows[2]).toHaveTextContent('Unavailable')
    expect(rows[2]).toHaveTextContent('PJ-2 · Until Mon 15 Mar')
    expect(within(panel('Faulty equipment')).getByRole('link', { name: 'View all faulty equipment' }))
      .toHaveAttribute('href', '/technical-support/faulty')
  })

  it("TS09 (happy): Equipment today shows units available out of the total and the kinds with the fewest left", async () => {
    const calls = stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => jsonResponse(200, [
        kind('Projector', 6, 2, 1, 3), kind('Webcam', 4, 4, 0, 0), kind('Laptop', 10, 0, 2, 8),
        kind('Tripod', 5, 2, 0, 3), kind('Speaker', 2, 1, 0, 1), kind('Mixer', 12, 0, 0, 12), kind('Badge', 30, 0, 0, 30),
      ]),
    })
    renderDashboard()

    const today = panel('Equipment today')
    expect(await within(today).findByText(/units available/)).toHaveTextContent('57 of 69 units available')
    const rows = within(within(today).getByRole('list', { name: 'Lowest today' })).getAllByRole('listitem')
    // Fewest left first; equal counts by name; only five.
    expect(rows.map((row) => row.querySelector('.row-link__title')?.textContent))
      .toEqual(['Webcam', 'Speaker', 'Projector', 'Tripod', 'Laptop'])
    expect(rows[0]).toHaveTextContent('0 left')
    expect(within(rows[0]).getByText('0 left')).toHaveClass('tech-left--none')
    expect(rows[2]).toHaveTextContent('3 left')
    expect(within(rows[2]).getByRole('link', { name: 'Projector' })).toHaveAccessibleDescription('3 left')
    expect(within(rows[2]).getByRole('link')).toHaveAttribute('href', '/technical-support/inventory/eq-Projector?from=2027-03-10&to=2027-03-10')
    expect(calls.some((call) => `GET ${call.url}` === CATALOGUE)).toBe(true)
    expect(within(today).getByRole('link', { name: 'View inventory' })).toHaveAttribute('href', '/technical-support/inventory')
  })

  it('TS09 (boundary): each block says when it has nothing to show', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, [report('rep-closed', '2027-03-09T00:00:00Z', 'CLOSED')]),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    expect(await within(panel('Requests to handle')).findByText('Nothing to handle right now.')).toBeInTheDocument()
    expect(await within(panel('Faulty equipment')).findByText('No equipment out of action.')).toBeInTheDocument()
    expect(await within(panel('Equipment today')).findByText('The equipment catalogue is empty.')).toBeInTheDocument()
    expect(screen.getByText('0 requests to handle · 0 items out of action')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Faulty equipment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Requests to handle' })).not.toBeInTheDocument()
  })

  it('TS09 (failure): a block that cannot load says so, and Try again reloads only that block', async () => {
    const tries = { requests: 0, periods: 0, catalogue: 0 }
    stubApi({
      [REQUESTS]: () => (++tries.requests === 1 ? jsonResponse(500, { message: 'Requests down' }) : jsonResponse(200, [request(1)])),
      [PERIODS]: () => (++tries.periods === 1 ? jsonResponse(500, { message: 'Blocks down' }) : jsonResponse(200, [period('1', '2027-03-08T00:00:00Z')])),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => (++tries.catalogue === 1 ? jsonResponse(500, { message: 'Stock down' }) : jsonResponse(200, [kind('Projector', 6, 2, 1, 3)])),
    })
    const user = userEvent.setup()
    renderDashboard()

    const alerts = await screen.findAllByRole('alert')
    expect(alerts).toHaveLength(3)
    expect(within(panel('Requests to handle')).getByRole('alert')).toHaveTextContent('The requests could not be loaded. Requests down')
    expect(within(panel('Faulty equipment')).getByRole('alert')).toHaveTextContent('Faulty equipment could not be loaded. Blocks down')
    expect(within(panel('Equipment today')).getByRole('alert')).toHaveTextContent('The inventory could not be loaded. Stock down')
    expect(screen.getByText('Some of what needs your attention could not be loaded.')).toBeInTheDocument()

    await user.click(within(panel('Requests to handle')).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Event 1' })).toBeInTheDocument()
    expect(tries).toEqual({ requests: 2, periods: 1, catalogue: 1 })
    expect(screen.getAllByRole('alert')).toHaveLength(2)

    await user.click(within(panel('Faulty equipment')).getByRole('button', { name: 'Try again' }))
    expect(await within(panel('Faulty equipment')).findByRole('link', { name: 'Projector' })).toBeInTheDocument()
    expect(screen.getByText('1 request to handle · 1 item out of action')).toBeInTheDocument()
    await user.click(within(panel('Equipment today')).getByRole('button', { name: 'Try again' }))
    expect(await within(panel('Equipment today')).findByText(/units available/)).toHaveTextContent('3 of 6 units available')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
