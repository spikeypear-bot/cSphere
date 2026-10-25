// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { TechnicalSupportDashboardPage } from '../dashboard/TechnicalSupportDashboardPage'
import { DamageReportsPage } from './DamageReportsPage'
import { DamageReportFormPage } from './DamageReportFormPage'
import { DamageReportDetailPage } from './DamageReportDetailPage'
import type { AffectedEvent, DamageReport } from './equipmentDamage.types'

const PROJECTOR = { equipmentId: 'eq-proj', equipmentName: 'Projector', totalQuantity: 6, serialised: true }
const CABLE = { equipmentId: 'eq-hdmi', equipmentName: 'HDMI Cable', totalQuantity: 30, serialised: false }

const EVENT: AffectedEvent = {
  outcomeId: 'out-1', eventId: 'event-1', eventName: 'Town Hall',
  eventStart: '2027-03-10T09:00:00+08:00', eventEnd: '2027-03-10T12:00:00+08:00', journeyStatus: 'CONFIRMED',
  requestedQuantity: 2, reservedQuantity: 2, affectedQuantity: 1, spareCovers: false, active: true,
  outcome: null, shortfall: 0, reducedBy: 0, replacementSerialNumber: null, decidedAt: null,
  resolution: null, resolutionNote: null, resolvedAt: null, additionalRequestId: null, additionalRequestStatus: null,
}

const REPORT: DamageReport = {
  reportId: 'rep-1', equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true,
  serialNumber: 'EPS-EBU04-7K2Q9M', quantity: 1, description: 'Lamp exploded',
  periodStart: '2027-03-01T00:00:00Z', periodEnd: null, markFaulty: true, faultyMarked: false,
  status: 'OPEN', reportedBy: 'ts-1', reportedByName: 'tech1', reportedAt: '2027-02-28T03:00:00Z', closedAt: null,
  canClose: false, affectedEvents: [EVENT],
}

const BULK_REPORT: DamageReport = {
  ...REPORT, reportId: 'rep-2', equipmentId: 'eq-hdmi', equipmentName: 'HDMI Cable', serialised: false,
  serialNumber: null, quantity: 5, markFaulty: false,
  affectedEvents: [{ ...EVENT, outcomeId: 'out-2', requestedQuantity: 10, reservedQuantity: 10, affectedQuantity: 10 }],
}

const iso = (local: string) => new Date(local).toISOString()
const plusDay = (local: string) => new Date(new Date(local).getTime() + 24 * 60 * 60 * 1000).toISOString()
const unitsUrl = (start: string, end: string) =>
  `GET /api/equipment/units?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support" element={<TechnicalSupportDashboardPage />} />
          <Route path="/technical-support/damaged-equipment" element={<DamageReportsPage />} />
          <Route path="/technical-support/damaged-equipment/new" element={<DamageReportFormPage />} />
          <Route path="/technical-support/damaged-equipment/:reportId" element={<DamageReportDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('TS09 Technical Support reports damaged equipment', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 AC1 (happy): the console dashboard links to faulty equipment, where damage is reported', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    renderAt('/technical-support')
    expect(screen.getByRole('link', { name: 'Faulty equipment' })).toHaveAttribute('href', '/technical-support/faulty')
    expect(await screen.findByText('No equipment is out of action.')).toBeInTheDocument()
  })

  it('TS09 AC6, AC15 (happy): lists reports newest first with how many events still need a decision', async () => {
    stubApi({ 'GET /api/equipment-damage-reports': () => jsonResponse(200, [REPORT, { ...BULK_REPORT, status: 'CLOSED' }]) })
    renderAt('/technical-support/damaged-equipment')

    const list = await screen.findByRole('list', { name: 'Damage reports' })
    const items = within(list).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Projector · EPS-EBU04-7K2Q9M')
    expect(items[0]).toHaveTextContent('1 awaiting a decision')
    expect(items[0]).toHaveTextContent('Open')
    expect(items[1]).toHaveTextContent('HDMI Cable · 5 damaged')
    expect(items[1]).toHaveTextContent('Closed')
    expect(screen.getByRole('img', { name: 'Projector' })).toBeInTheDocument()
  })

  it('TS09 AC22 (failure): a list that cannot load says so and Try again reloads it', async () => {
    let calls = 0
    stubApi({
      'GET /api/equipment-damage-reports': () => (++calls === 1
        ? jsonResponse(500, { message: 'Server unavailable' })
        : jsonResponse(200, [REPORT])),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment')

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('list', { name: 'Damage reports' })).toBeInTheDocument()
  })

  it('TS09 AC1, AC2, AC4 (happy): reports a serialised unit by serial number, asking to mark it Faulty', async () => {
    const start = '2027-03-01T09:00'
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR, CABLE]),
      [unitsUrl(iso(start), plusDay(start))]: () => jsonResponse(200, [
        { equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'EPS-EBU04-7K2Q9M', status: 'Available' },
        { equipmentId: 'eq-hdmi', equipmentName: 'HDMI Cable', serialNumber: 'IGNORED', status: 'Available' },
      ]),
      'POST /api/equipment-damage-reports': () => jsonResponse(201, REPORT),
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, REPORT),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/new')

    await user.selectOptions(await screen.findByLabelText('Equipment'), 'eq-proj')
    fireEvent.change(screen.getByLabelText('Damage starts'), { target: { value: start } })
    const unit = screen.getByLabelText('Damaged unit')
    await screen.findByRole('option', { name: 'EPS-EBU04-7K2Q9M (Available)' })
    expect(within(unit).queryByRole('option', { name: /IGNORED/ })).not.toBeInTheDocument()
    await user.selectOptions(unit, 'EPS-EBU04-7K2Q9M')
    await user.click(screen.getByLabelText(/Also mark this unit Faulty/))
    await user.type(screen.getByLabelText('Description'), '  Lamp exploded  ')
    await user.click(screen.getByRole('button', { name: 'Save damage report' }))

    expect(await screen.findByRole('heading', { name: 'Projector · EPS-EBU04-7K2Q9M' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      equipmentId: 'eq-proj', serialNumber: 'EPS-EBU04-7K2Q9M', quantity: null, description: 'Lamp exploded',
      periodStart: iso(start), periodEnd: null, markFaulty: true,
    })
  })

  it('TS09 AC1, AC3 (happy): reports a non-serialised quantity with an end date', async () => {
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR, CABLE]),
      'POST /api/equipment-damage-reports': () => jsonResponse(201, BULK_REPORT),
      'GET /api/equipment-damage-reports/rep-2': () => jsonResponse(200, BULK_REPORT),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/new')

    await user.selectOptions(await screen.findByLabelText('Equipment'), 'eq-hdmi')
    expect(screen.queryByLabelText('Damaged unit')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Also mark this unit Faulty/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Damage starts'), { target: { value: '2027-03-01T09:00' } })
    fireEvent.change(screen.getByLabelText('Damage ends (optional)'), { target: { value: '2027-03-05T09:00' } })
    await user.clear(screen.getByLabelText('Units damaged'))
    await user.type(screen.getByLabelText('Units damaged'), '5')
    await user.type(screen.getByLabelText('Description'), 'Connectors bent')
    await user.click(screen.getByRole('button', { name: 'Save damage report' }))

    expect(await screen.findByRole('heading', { name: 'HDMI Cable' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      equipmentId: 'eq-hdmi', serialNumber: null, quantity: 5, periodEnd: iso('2027-03-05T09:00'), markFaulty: false,
    })
  })

  it('TS09 AC1, AC3, AC7 (negative): missing details, an end not after the start and too many units are refused before sending', async () => {
    const calls = stubApi({ 'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR, CABLE]) })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/new')

    await user.click(await screen.findByRole('button', { name: 'Save damage report' }))
    expect(screen.getByText('Choose the damaged equipment.')).toBeInTheDocument()
    expect(screen.getByText('Describe the damage.')).toBeInTheDocument()
    expect(screen.getByText('Enter when the damage starts.')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Equipment'), 'eq-hdmi')
    fireEvent.change(screen.getByLabelText('Damage starts'), { target: { value: '2027-03-01T09:00' } })
    fireEvent.change(screen.getByLabelText('Damage ends (optional)'), { target: { value: '2027-03-01T09:00' } })
    await user.clear(screen.getByLabelText('Units damaged'))
    await user.type(screen.getByLabelText('Units damaged'), '31')
    await user.type(screen.getByLabelText('Description'), '   ')
    await user.click(screen.getByRole('button', { name: 'Save damage report' }))

    expect(screen.getByText('The end must be after the start.')).toBeInTheDocument()
    expect(screen.getByText('Enter a whole number from 1 to 30.')).toBeInTheDocument()
    expect(screen.getByText('Describe the damage.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('TS09 AC2 (negative): a serialised kind needs a unit before it can be sent', async () => {
    const start = '2027-03-01T09:00'
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      [unitsUrl(iso(start), plusDay(start))]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/new')

    await user.selectOptions(await screen.findByLabelText('Equipment'), 'eq-proj')
    fireEvent.change(screen.getByLabelText('Damage starts'), { target: { value: start } })
    await user.type(screen.getByLabelText('Description'), 'Cracked lens')
    await user.click(screen.getByRole('button', { name: 'Save damage report' }))

    expect(screen.getByText('Choose the damaged unit.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('TS09 AC13 (failure): a report the server refuses shows its reason and stays on the form', async () => {
    stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [CABLE]),
      'POST /api/equipment-damage-reports': () => jsonResponse(400, { message: 'Only 30 HDMI Cable are owned.' }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/new')

    await user.selectOptions(await screen.findByLabelText('Equipment'), 'eq-hdmi')
    fireEvent.change(screen.getByLabelText('Damage starts'), { target: { value: '2027-03-01T09:00' } })
    await user.type(screen.getByLabelText('Description'), 'Connectors bent')
    await user.click(screen.getByRole('button', { name: 'Save damage report' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Only 30 HDMI Cable are owned.')
    expect(screen.getByRole('heading', { name: 'Report damaged equipment' })).toBeInTheDocument()
  })

  it('TS09 AC6, AC9 (happy): shows each affected event and records Covered by spare when spares cover it', async () => {
    const spare = { ...REPORT, affectedEvents: [{ ...EVENT, reservedQuantity: 3, spareCovers: true }] }
    const covered = { ...spare, canClose: true, affectedEvents: [{ ...spare.affectedEvents[0], outcome: 'COVERED' as const }] }
    const calls = stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, spare),
      'POST /api/equipment-damage-reports/rep-1/outcomes/out-1': () => jsonResponse(200, covered),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-1')

    const card = within(await screen.findByRole('list', { name: 'Affected events' })).getByRole('listitem')
    expect(card).toHaveTextContent('Town Hall')
    expect(card).toHaveTextContent('Requested2')
    expect(card).toHaveTextContent('Reserved3')
    expect(card).toHaveTextContent('Spares cover itYes')
    expect(within(card).getByRole('button', { name: 'Mark short' })).toBeDisabled()
    await user.click(within(card).getByRole('button', { name: 'Covered by spare' }))

    expect(await screen.findByText('Town Hall: covered by spare.')).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ decision: 'COVERED', replacementSerialNumber: null, reduceBy: null })
    expect(within(card).getByText('Covered by spare', { selector: '.chip' })).toBeInTheDocument()
  })

  it('TS09 AC10 (happy): replaces the damaged unit with an available unit of the same kind', async () => {
    const replaced = { ...REPORT, affectedEvents: [{ ...EVENT, outcome: 'REPLACED' as const, replacementSerialNumber: 'EPS-EBU04-AAAA22' }] }
    const calls = stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, REPORT),
      [unitsUrl(iso(EVENT.eventStart), iso(EVENT.eventEnd))]: () => jsonResponse(200, [
        { equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'EPS-EBU04-7K2Q9M', status: 'Available' },
        { equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'EPS-EBU04-FAULTY', status: 'Faulty' },
        { equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'EPS-EBU04-AAAA22', status: 'Available' },
      ]),
      'POST /api/equipment-damage-reports/rep-1/outcomes/out-1': () => jsonResponse(200, replaced),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-1')

    await user.click(await screen.findByRole('button', { name: 'Replace unit' }))
    const select = await screen.findByLabelText('Replacement unit for Town Hall')
    // Neither the damaged unit nor a Faulty one is offered.
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose a unit…', 'EPS-EBU04-AAAA22'])
    await user.selectOptions(select, 'EPS-EBU04-AAAA22')
    await user.click(screen.getByRole('button', { name: 'Use this unit' }))

    expect(await screen.findByText('Town Hall: replaced with unit EPS-EBU04-AAAA22.')).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ decision: 'REPLACE', replacementSerialNumber: 'EPS-EBU04-AAAA22', reduceBy: null })
    expect(screen.getByText('Replaced with unit EPS-EBU04-AAAA22', { selector: '.chip' })).toBeInTheDocument()
  })

  it('TS09 AC12 (happy): with no replacement the event is marked short', async () => {
    const short = { ...REPORT, canClose: true, affectedEvents: [{ ...EVENT, outcome: 'SHORT' as const, shortfall: 1 }] }
    const calls = stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, REPORT),
      'POST /api/equipment-damage-reports/rep-1/outcomes/out-1': () => jsonResponse(200, short),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-1')

    expect(await screen.findByRole('button', { name: 'Covered by spare' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Mark short' }))

    expect(await screen.findByText('Short by 1', { selector: '.chip' })).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ decision: 'SHORT', replacementSerialNumber: null, reduceBy: null })
    expect(screen.queryByRole('button', { name: 'Mark short' })).not.toBeInTheDocument()
  })

  it('TS09 AC13, AC14 (conflict): a replacement the server refuses shows its reason and nothing changes', async () => {
    stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, REPORT),
      [unitsUrl(iso(EVENT.eventStart), iso(EVENT.eventEnd))]: () => jsonResponse(200, [
        { equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'EPS-EBU04-AAAA22', status: 'Available' },
      ]),
      'POST /api/equipment-damage-reports/rep-1/outcomes/out-1': () =>
        jsonResponse(409, { message: 'Unit EPS-EBU04-AAAA22 is already reserved during this reservation.' }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-1')

    await user.click(await screen.findByRole('button', { name: 'Replace unit' }))
    await user.selectOptions(await screen.findByLabelText('Replacement unit for Town Hall'), 'EPS-EBU04-AAAA22')
    await user.click(screen.getByRole('button', { name: 'Use this unit' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already reserved during this reservation')
    expect(screen.getByText('Awaiting a decision', { selector: '.chip' })).toBeInTheDocument()
  })

  it('TS09 AC11, AC12 (happy): a non-serialised event is reduced and the remaining shortfall shown', async () => {
    const reduced = {
      ...BULK_REPORT, canClose: true,
      affectedEvents: [{ ...BULK_REPORT.affectedEvents[0], reservedQuantity: 7, reducedBy: 3, outcome: 'SHORT' as const, shortfall: 3 }],
    }
    const calls = stubApi({
      'GET /api/equipment-damage-reports/rep-2': () => jsonResponse(200, BULK_REPORT),
      'POST /api/equipment-damage-reports/rep-2/outcomes/out-2': () => jsonResponse(200, reduced),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-2')

    expect(await screen.findByText(/reservations fit the working units/)).toBeInTheDocument()
    const input = screen.getByLabelText('Units to take off Town Hall')
    await user.clear(input)
    await user.type(input, '3')
    await user.click(screen.getByRole('button', { name: 'Apply' }))

    expect(await screen.findByText('Short by 3', { selector: '.chip' })).toBeInTheDocument()
    expect(calls.at(-1)?.body).toEqual({ decision: 'REDUCE', replacementSerialNumber: null, reduceBy: 3 })
    expect(screen.getByText('Reduced by').nextSibling).toHaveTextContent('3')
  })

  it('TS09 AC11 (boundary): a reduction above what the event holds cannot be sent', async () => {
    const calls = stubApi({ 'GET /api/equipment-damage-reports/rep-2': () => jsonResponse(200, BULK_REPORT) })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-2')

    const input = await screen.findByLabelText('Units to take off Town Hall')
    await user.clear(input)
    await user.type(input, '11')
    expect(screen.getByText('Enter a whole number from 0 to 10.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    await user.clear(input)
    await user.type(input, '10')
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('TS09 AC15 (happy): the report closes only once every event has an outcome', async () => {
    const decided = { ...REPORT, canClose: true, affectedEvents: [{ ...EVENT, outcome: 'SHORT' as const, shortfall: 1 }] }
    const closed = { ...decided, status: 'CLOSED' as const, faultyMarked: true, closedAt: '2027-03-01T05:00:00Z' }
    let decidedYet = false
    stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, decidedYet ? decided : REPORT),
      'POST /api/equipment-damage-reports/rep-1/close': () => jsonResponse(200, closed),
    })
    const user = userEvent.setup()
    const first = renderAt('/technical-support/damaged-equipment/rep-1')

    expect(await screen.findByRole('button', { name: 'Close report' })).toBeDisabled()
    expect(screen.getByText(/once every affected event has an outcome/)).toBeInTheDocument()
    first.unmount()

    decidedYet = true
    renderAt('/technical-support/damaged-equipment/rep-1')
    await user.click(await screen.findByRole('button', { name: 'Close report' }))

    expect(await screen.findByText('Report closed.')).toBeInTheDocument()
    expect(screen.getByText('Unit marked Faulty')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Close report' })).not.toBeInTheDocument()
  })

  it('TS09 AC6 (negative): an event cancelled or finished since needs no outcome and offers no actions', async () => {
    stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => jsonResponse(200, { ...REPORT, affectedEvents: [{ ...EVENT, active: false }] }),
    })
    renderAt('/technical-support/damaged-equipment/rep-1')

    expect(await screen.findByText(/cancelled or has finished, so it needs no outcome/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark short' })).not.toBeInTheDocument()
  })

  it('TS09 AC22 (failure): a report that cannot load says so and Try again reloads it', async () => {
    let calls = 0
    stubApi({
      'GET /api/equipment-damage-reports/rep-1': () => (++calls === 1
        ? jsonResponse(500, { message: 'Server unavailable' })
        : jsonResponse(200, REPORT)),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/damaged-equipment/rep-1')

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('list', { name: 'Affected events' })).toBeInTheDocument()
  })
})
