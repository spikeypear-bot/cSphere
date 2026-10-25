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
// "Today → tomorrow" for a clock fixed at 10 March 2027, 15:30 local time.
const NOW = new Date(2027, 2, 10, 15, 30)
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

function period(id: string, start: string, status = 'Faulty') {
  return { id, equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: `PJ-${id}`, status, start, end: null }
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

const cardOf = (title: string) => screen.getByRole('link', { name: title }).closest('.card') as HTMLElement

describe('TS09 Technical Support dashboard', () => {
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

  it('TS09 TS01 (happy): Incoming requests shows the five most urgent with requester and items, each linking to its request', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, [
        request(1, { isShortfallRequest: true }),
        request(2, { requestedBy: null, items: [] }),
        request(3), request(4),
        request(5, { items: [{ equipmentId: 'a', equipmentName: 'Projector', quantity: 2 }, { equipmentId: 'b', equipmentName: 'Microphone', quantity: 1 }] }),
        request(6),
      ]),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    const rows = within(await screen.findByRole('list', { name: 'Incoming requests' })).getAllByRole('listitem')
    // Top five in the server's order (soonest first); the sixth is left for the list page.
    expect(rows.map((row) => within(row).getByRole('link').textContent)).toEqual(['Event 1', 'Event 2', 'Event 3', 'Event 4', 'Event 5'])
    expect(within(rows[0]).getByRole('link')).toHaveAttribute('href', '/technical-support/requests/req-1')
    expect(rows[0]).toHaveTextContent('Shortfall')
    expect(rows[0]).toHaveTextContent('ec1 · 2 × Projector')
    expect(rows[1]).toHaveTextContent('No coordinator assigned · No itemised equipment')
    expect(rows[1]).not.toHaveTextContent('Shortfall')
    expect(rows[4]).toHaveTextContent('2 × Projector, 1 × Microphone')
    expect(screen.getByRole('link', { name: 'Incoming requests' })).toHaveAttribute('href', '/technical-support/requests')
  })

  it('TS09 TS03 (happy): Faulty equipment merges current blocks with open reports, newest first, without closed reports', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, [
        period('1', '2027-03-08T00:00:00Z'),
        period('2', '2027-03-01T00:00:00Z', 'Unavailable'),
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

    const rows = within(await screen.findByRole('list', { name: 'Faulty equipment' })).getAllByRole('listitem')
    expect(rows.map((row) => within(row).getByRole('link').textContent)).toEqual([
      'HDMI Cable · 3 units', 'Projector · PJ-1', 'Projector · PJ-2', 'Projector · PJ-3', 'HDMI Cable · 3 units',
    ])
    expect(within(rows[0]).getByRole('link')).toHaveAttribute('href', '/technical-support/faulty/reports/rep-new')
    expect(rows[0]).toHaveTextContent('Damage report')
    expect(within(rows[1]).getByRole('link')).toHaveAttribute('href', '/technical-support/inventory/eq-proj')
    expect(rows[1]).toHaveTextContent('Faulty')
    expect(rows[1]).toHaveTextContent('until further notice')
    expect(rows[2]).toHaveTextContent('Unavailable')
    expect(screen.queryByRole('link', { name: /rep-closed/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Faulty equipment' })).toHaveAttribute('href', '/technical-support/faulty')
  })

  it("TS09 (happy): Inventory sums today's catalogue window and names what has none left", async () => {
    const calls = stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, []),
      [CATALOGUE]: () => jsonResponse(200, [kind('Projector', 6, 2, 1, 3), kind('Webcam', 4, 4, 0, 0), kind('Laptop', 10, 0, 2, 8)]),
    })
    renderDashboard()

    const card = cardOf('Inventory')
    expect(await within(card).findByText('Kinds')).toBeInTheDocument()
    expect(within(card).getByText('Kinds').nextSibling).toHaveTextContent('3')
    expect(within(card).getByText('Units available today').nextSibling).toHaveTextContent('11')
    expect(within(card).getByText('Reserved').nextSibling).toHaveTextContent('6')
    expect(within(card).getByText('Out of action').nextSibling).toHaveTextContent('3')
    expect(card).toHaveTextContent('None left today: Webcam')
    expect(calls.some((call) => `GET ${call.url}` === CATALOGUE)).toBe(true)
    expect(screen.getByRole('link', { name: 'Inventory' })).toHaveAttribute('href', '/technical-support/inventory')
  })

  it('TS09 (boundary): each card says when it has nothing to show', async () => {
    stubApi({
      [REQUESTS]: () => jsonResponse(200, []),
      [PERIODS]: () => jsonResponse(200, []),
      [REPORTS]: () => jsonResponse(200, [report('rep-closed', '2027-03-09T00:00:00Z', 'CLOSED')]),
      [CATALOGUE]: () => jsonResponse(200, []),
    })
    renderDashboard()

    expect(await screen.findByText('No equipment requests are awaiting review.')).toBeInTheDocument()
    expect(await screen.findByText('No equipment is out of action.')).toBeInTheDocument()
    expect(await screen.findByText('The equipment catalogue is empty.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Faulty equipment' })).not.toBeInTheDocument()
  })

  it('TS09 (failure): a card that cannot load says so, and Try again reloads only that card', async () => {
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
    expect(within(cardOf('Incoming requests')).getByRole('alert')).toHaveTextContent('The requests could not be loaded. Requests down')
    expect(within(cardOf('Faulty equipment')).getByRole('alert')).toHaveTextContent('Faulty equipment could not be loaded. Blocks down')
    expect(within(cardOf('Inventory')).getByRole('alert')).toHaveTextContent('The inventory could not be loaded. Stock down')

    await user.click(within(cardOf('Incoming requests')).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Event 1' })).toBeInTheDocument()
    expect(tries).toEqual({ requests: 2, periods: 1, catalogue: 1 })
    expect(screen.getAllByRole('alert')).toHaveLength(2)

    await user.click(within(cardOf('Faulty equipment')).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Projector · PJ-1' })).toBeInTheDocument()
    await user.click(within(cardOf('Inventory')).getByRole('button', { name: 'Try again' }))
    expect(await within(cardOf('Inventory')).findByText('Units available today')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
