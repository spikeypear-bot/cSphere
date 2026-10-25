// @stories TS09 TS03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { EquipmentDetailPage } from './EquipmentDetailPage'
import { InventoryPage } from './InventoryPage'

// The clock is fixed at 10 March 2027, 15:30 local time; the default window
// is today and the next seven days: [10 Mar 00:00, 18 Mar 00:00).
const NOW = new Date(2027, 2, 10, 15, 30)
const day = (d: number, month = 2) => new Date(2027, month, d).toISOString()
const query = (from: string, to: string) => `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
const DEFAULT = query(day(10), day(18))

const kind = (id: string, name: string, figures: [number, number, number, number], serialised = false) => ({
  equipmentId: id, equipmentName: name, serialised,
  totalQuantity: figures[0], reservedQuantity: figures[1], outOfActionQuantity: figures[2], availableQuantity: figures[3],
})
const CATALOGUE = [
  kind('eq-proj', 'Projector', [6, 2, 1, 3], true),
  kind('eq-lap', 'Laptop', [10, 0, 0, 10]),
  kind('eq-stand', 'iPad Floor Stand', [4, 4, 0, 0]),
]

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/inventory" element={<InventoryPage />} />
          <Route path="/technical-support/inventory/:equipmentId" element={<EquipmentDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const kindsShown = () => within(screen.getByRole('list', { name: 'Equipment kinds' }))
  .getAllByRole('heading').map((heading) => heading.textContent)

describe('TS09 inventory by date and equipment detail', () => {
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

  it('TS09 (happy): defaults to today and the next seven days and shows each kind with its figures', async () => {
    const calls = stubApi({ [`GET /api/equipment/catalogue?${DEFAULT}`]: () => jsonResponse(200, CATALOGUE) })
    renderAt('/technical-support/inventory')

    const list = await screen.findByRole('list', { name: 'Equipment kinds' })
    expect(screen.getByLabelText('From')).toHaveValue('2027-03-10')
    expect(screen.getByLabelText('To (inclusive)')).toHaveValue('2027-03-17')
    const projector = within(list).getAllByRole('listitem')[0]
    expect(within(projector).getByRole('img', { name: 'Projector' })).toBeInTheDocument()
    expect(projector).toHaveTextContent('Audiovisual · tracked by unit')
    expect(projector).toHaveTextContent('Total6')
    expect(projector).toHaveTextContent('Reserved2')
    expect(projector).toHaveTextContent('Out of action1')
    expect(projector).toHaveTextContent('Available3')
    expect(within(projector).getByRole('link', { name: 'Projector' }))
      .toHaveAttribute('href', '/technical-support/inventory/eq-proj?from=2027-03-10&to=2027-03-17')
    expect(screen.getByRole('img', { name: 'No photo of iPad Floor Stand' })).toBeInTheDocument()
    expect(calls.map((call) => call.url)).toEqual([`/api/equipment/catalogue?${DEFAULT}`])
  })

  it('TS09 (boundary): new dates reload the window, a single day counts in full, and a last day before the first is refused', async () => {
    const calls = stubApi({
      [`GET /api/equipment/catalogue?${DEFAULT}`]: () => jsonResponse(200, CATALOGUE),
      [`GET /api/equipment/catalogue?${query(day(12), day(18))}`]: () => jsonResponse(200, CATALOGUE),
      [`GET /api/equipment/catalogue?${query(day(12), day(13))}`]: () => jsonResponse(200, [CATALOGUE[1]]),
    })
    renderAt('/technical-support/inventory')
    await screen.findByRole('list', { name: 'Equipment kinds' })

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2027-03-12' } })
    fireEvent.change(screen.getByLabelText('To (inclusive)'), { target: { value: '2027-03-12' } })
    expect(await screen.findByRole('link', { name: 'Laptop' })).toBeInTheDocument()
    expect(kindsShown()).toEqual(['Laptop'])

    fireEvent.change(screen.getByLabelText('To (inclusive)'), { target: { value: '2027-03-11' } })
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a last day on or after the first day.')
    expect(screen.queryByRole('list', { name: 'Equipment kinds' })).not.toBeInTheDocument()
    // Each change of day reloads; the last day is included (window ends the midnight after it).
    expect(calls.map((call) => call.url)).toEqual([
      `/api/equipment/catalogue?${DEFAULT}`,
      `/api/equipment/catalogue?${query(day(12), day(18))}`,
      `/api/equipment/catalogue?${query(day(12), day(13))}`,
    ])
  })

  it('TS09 (happy): the category filter and search narrow the kinds without asking the server again', async () => {
    const calls = stubApi({ [`GET /api/equipment/catalogue?${DEFAULT}`]: () => jsonResponse(200, CATALOGUE) })
    const user = userEvent.setup()
    renderAt('/technical-support/inventory')
    await screen.findByRole('list', { name: 'Equipment kinds' })

    await user.selectOptions(screen.getByLabelText('Category'), 'Computing & Streaming')
    expect(kindsShown()).toEqual(['Laptop'])
    await user.selectOptions(screen.getByLabelText('Category'), 'Other')
    expect(kindsShown()).toEqual(['iPad Floor Stand'])
    await user.selectOptions(screen.getByLabelText('Category'), '')
    await user.type(screen.getByLabelText('Search'), 'PROJ')
    expect(kindsShown()).toEqual(['Projector'])
    await user.clear(screen.getByLabelText('Search'))
    await user.type(screen.getByLabelText('Search'), 'drone')
    expect(screen.getByText('No equipment matches these filters.')).toBeInTheDocument()
    expect(calls).toHaveLength(1)
  })

  it('TS09 (failure): an inventory that cannot load says so and Try again reloads it', async () => {
    let tries = 0
    stubApi({
      [`GET /api/equipment/catalogue?${DEFAULT}`]: () => (++tries === 1
        ? jsonResponse(500, { message: 'Server unavailable' }) : jsonResponse(200, CATALOGUE)),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/inventory')

    expect(await screen.findByRole('alert')).toHaveTextContent('The inventory could not be loaded. Server unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('list', { name: 'Equipment kinds' })).toBeInTheDocument()
  })

  it('TS09 TS03 (happy): one kind shows its units and blocks, its reservations for the window, and its damage history', async () => {
    const span = query(day(12), day(15))
    const calls = stubApi({
      'GET /api/equipment/eq-proj': () => jsonResponse(200, {
        equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true, totalQuantity: 2,
        units: [
          { serialNumber: 'PJ-1', status: 'Faulty', statusPeriods: [{ id: 'blk-1', status: 'Faulty', start: '2027-03-09T00:00:00Z', end: null }] },
          { serialNumber: 'PJ-2', status: 'Available', statusPeriods: [] },
        ],
      }),
      [`GET /api/equipment/eq-proj/reservations?${span}`]: () => jsonResponse(200, [{
        logId: 'log-1', eventId: 'ev-1', equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 1, serialNumber: 'PJ-2',
        loanedFrom: '2027-03-12T01:00:00Z', loanedUntil: '2027-03-12T04:00:00Z', eventName: 'Gala',
      }]),
      'GET /api/equipment-damage-reports?equipmentId=eq-proj': () => jsonResponse(200, [{
        reportId: 'rep-1', equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true, serialNumber: 'PJ-1', quantity: 1,
        description: 'Lamp exploded', periodStart: '2027-03-01T00:00:00Z', periodEnd: null, blockStatus: 'FAULTY', blockApplied: true,
        status: 'CLOSED', reportedBy: 't-1', reportedByName: 'ts1', reportedAt: '2027-03-01T00:00:00Z', closedAt: null, canClose: false, affectedEvents: [],
      }]),
    })
    renderAt('/technical-support/inventory/eq-proj?from=2027-03-12&to=2027-03-14')

    expect(await screen.findByRole('heading', { level: 1, name: 'Projector' })).toBeInTheDocument()
    const units = within(screen.getByRole('list', { name: 'Units' })).getAllByRole('listitem')
    expect(units[0]).toHaveTextContent('PJ-1Faulty now')
    expect(units[0]).toHaveTextContent('Faulty: From')
    expect(within(units[0]).getByRole('button', { name: 'Remove (mark available)' })).toBeEnabled()
    expect(units[1]).toHaveTextContent('PJ-2Available now')
    expect(units[1]).toHaveTextContent('No Faulty or Unavailable periods.')
    expect(within(units[1]).getByRole('link', { name: 'Report a problem with PJ-2' }))
      .toHaveAttribute('href', '/technical-support/faulty/new?equipment=eq-proj&serial=PJ-2')

    const reservation = within(await screen.findByRole('list', { name: 'Reservations' })).getByRole('listitem')
    expect(reservation).toHaveTextContent('Gala')
    expect(reservation).toHaveTextContent('1 × Projector (unit PJ-2)')
    expect(screen.getByLabelText('From')).toHaveValue('2027-03-12')

    const history = within(await screen.findByRole('list', { name: 'Damage history' })).getByRole('listitem')
    expect(within(history).getByRole('link', { name: 'PJ-1: Lamp exploded' })).toHaveAttribute('href', '/technical-support/faulty/reports/rep-1')
    expect(history).toHaveTextContent('Closed')
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('TS09 (boundary): a quantity kind with nothing reserved or reported says so in each section', async () => {
    stubApi({
      'GET /api/equipment/eq-lap': () => jsonResponse(200, {
        equipmentId: 'eq-lap', equipmentName: 'Laptop', serialised: false, totalQuantity: 10, units: [],
      }),
      [`GET /api/equipment/eq-lap/reservations?${DEFAULT}`]: () => jsonResponse(200, []),
      'GET /api/equipment-damage-reports?equipmentId=eq-lap': () => jsonResponse(200, []),
    })
    renderAt('/technical-support/inventory/eq-lap')

    expect(await screen.findByText(/not tracked one by one/)).toBeInTheDocument()
    expect(await screen.findByText('Nothing is reserved in these dates.')).toBeInTheDocument()
    expect(await screen.findByText('No damage has been reported for this equipment.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Units' })).not.toBeInTheDocument()
  })

  it('TS09 (failure): reservations that cannot load fail only their section, and Try again reloads them', async () => {
    let tries = 0
    stubApi({
      'GET /api/equipment/eq-lap': () => jsonResponse(200, {
        equipmentId: 'eq-lap', equipmentName: 'Laptop', serialised: false, totalQuantity: 10, units: [],
      }),
      [`GET /api/equipment/eq-lap/reservations?${DEFAULT}`]: () => (++tries === 1
        ? jsonResponse(500, { message: 'Server unavailable' }) : jsonResponse(200, [])),
      'GET /api/equipment-damage-reports?equipmentId=eq-lap': () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/inventory/eq-lap')

    expect(await screen.findByRole('alert')).toHaveTextContent('The reservations could not be loaded. Server unavailable')
    expect(await screen.findByText('No damage has been reported for this equipment.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Nothing is reserved in these dates.')).toBeInTheDocument()
  })

  it('TS09 (cross-cutting): each kind is one clickable card named after it, its figures read as the description', async () => {
    stubApi({ [`GET /api/equipment/catalogue?${DEFAULT}`]: () => jsonResponse(200, CATALOGUE) })
    renderAt('/technical-support/inventory')

    const projector = within(await screen.findByRole('list', { name: 'Equipment kinds' })).getAllByRole('listitem')[0]
    const links = within(projector).getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAccessibleName('Projector')
    expect(links[0]).toHaveAccessibleDescription(/Available\s*3/)
  })
})
