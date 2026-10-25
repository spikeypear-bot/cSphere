// @stories TS09 TS03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { DamageReportDetailPage } from '../damage/DamageReportDetailPage'
import { FaultyEquipmentPage } from './FaultyEquipmentPage'
import { ReportProblemPage } from './ReportProblemPage'

const BLOCKS = 'GET /api/equipment/status-periods?current=true'
const REPORTS = 'GET /api/equipment-damage-reports'
const PROJECTOR = { equipmentId: 'eq-proj', equipmentName: 'Projector', totalQuantity: 3, serialised: true }
const PROJECTOR_DETAIL = {
  equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true, totalQuantity: 3,
  units: [
    { serialNumber: 'PJ-1', status: 'Available', statusPeriods: [] },
    { serialNumber: 'PJ-2', status: 'Available', statusPeriods: [] },
  ],
}
const PERIODS_URL = 'POST /api/equipment/eq-proj/units/PJ-2/periods'
const iso = (local: string) => new Date(local).toISOString()

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/faulty" element={<FaultyEquipmentPage />} />
          <Route path="/technical-support/faulty/new" element={<ReportProblemPage />} />
          <Route path="/technical-support/faulty/reports/:reportId" element={<DamageReportDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function fillSerialised(user: ReturnType<typeof userEvent.setup>, { end }: { end?: string } = {}) {
  await user.selectOptions(await screen.findByLabelText('Equipment'), 'eq-proj')
  await screen.findByRole('option', { name: 'PJ-2 (Available)' })
  await user.selectOptions(screen.getByLabelText('Unit'), 'PJ-2')
  fireEvent.change(screen.getByLabelText('Starts'), { target: { value: '2027-04-01T09:00' } })
  if (end) fireEvent.change(screen.getByLabelText('Ends (optional)'), { target: { value: end } })
  await user.type(screen.getByLabelText('Description'), 'Fan noise')
}

describe('TS09 faulty equipment and the report-a-problem form', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 TS03 (happy): a serialised unit no event holds is blocked through TS03 only, and the list says so', async () => {
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      'GET /api/equipment/eq-proj': () => jsonResponse(200, PROJECTOR_DETAIL),
      [PERIODS_URL]: () => jsonResponse(204, null),
      [BLOCKS]: () => jsonResponse(200, [{
        id: 'p-1', equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'PJ-2',
        status: 'Faulty', start: iso('2027-04-01T09:00'), end: iso('2027-04-03T09:00'),
      }]),
      [REPORTS]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/faulty/new')

    await fillSerialised(user, { end: '2027-04-03T09:00' })
    expect(screen.getByLabelText('Status')).toHaveValue('Faulty')
    await user.click(screen.getByRole('button', { name: 'Report problem' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Faulty equipment' })).toBeInTheDocument()
    expect(screen.getByText('Projector PJ-2 marked Faulty for the chosen dates.')).toBeInTheDocument()
    const blocked = within(await screen.findByRole('list', { name: 'Blocked units' })).getByRole('listitem')
    expect(blocked).toHaveTextContent('Projector · PJ-2')
    expect(blocked).toHaveTextContent('Faulty')
    const posts = calls.filter((call) => call.method === 'POST')
    expect(posts.map((call) => call.url)).toEqual(['/api/equipment/eq-proj/units/PJ-2/periods'])
    expect(posts[0].body).toEqual({ status: 'Faulty', start: iso('2027-04-01T09:00'), end: iso('2027-04-03T09:00') })
  })

  it('TS09 TS03 (negative): any other TS03 refusal is shown and no damage report is created', async () => {
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      'GET /api/equipment/eq-proj': () => jsonResponse(200, PROJECTOR_DETAIL),
      [PERIODS_URL]: () => jsonResponse(409, { message: 'Something else conflicts.' }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/faulty/new')

    await fillSerialised(user)
    await user.click(screen.getByRole('button', { name: 'Report problem' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Something else conflicts. Nothing was changed.')
    expect(screen.getByRole('heading', { name: 'Report a problem' })).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST').map((call) => call.url)).toEqual(['/api/equipment/eq-proj/units/PJ-2/periods'])
  })

  it('TS09 (failure): when the fallback report fails too, the form stays with the reason', async () => {
    stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      'GET /api/equipment/eq-proj': () => jsonResponse(200, PROJECTOR_DETAIL),
      [PERIODS_URL]: () => jsonResponse(409, { message: 'This unit is already committed to an event during that time.' }),
      'POST /api/equipment-damage-reports': () => jsonResponse(400, { message: 'The description is too long.' }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/faulty/new')

    await fillSerialised(user)
    await user.selectOptions(screen.getByLabelText('Status'), 'Faulty')
    await user.click(screen.getByRole('button', { name: 'Report problem' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The description is too long.')
    expect(screen.getByRole('heading', { name: 'Report a problem' })).toBeInTheDocument()
  })

  it('TS09 (boundary): an end equal to the start is refused before anything is sent', async () => {
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      'GET /api/equipment/eq-proj': () => jsonResponse(200, PROJECTOR_DETAIL),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/faulty/new')

    await fillSerialised(user, { end: '2027-04-01T09:00' })
    await user.click(screen.getByRole('button', { name: 'Report problem' }))

    expect(screen.getByText('The end must be after the start.')).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
  })

  it('TS09 (happy): a link from an equipment page opens the form with the kind and unit chosen', async () => {
    stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [PROJECTOR]),
      'GET /api/equipment/eq-proj': () => jsonResponse(200, PROJECTOR_DETAIL),
    })
    renderAt('/technical-support/faulty/new?equipment=eq-proj&serial=PJ-1')

    expect(await screen.findByLabelText('Equipment')).toHaveValue('eq-proj')
    await screen.findByRole('option', { name: 'PJ-1 (Available)' })
    expect(screen.getByLabelText('Unit')).toHaveValue('PJ-1')
  })

  it('TS09 (happy): blocked units show a photo, or a placeholder for a kind without one, and link to the kind', async () => {
    stubApi({
      [BLOCKS]: () => jsonResponse(200, [
        { id: 'p-1', equipmentId: 'eq-proj', equipmentName: 'Projector', serialNumber: 'PJ-1', status: 'Unavailable', start: '2027-04-01T01:00:00Z', end: null },
        { id: 'p-2', equipmentId: 'eq-stand', equipmentName: 'iPad Floor Stand', serialNumber: 'ST-1', status: 'Faulty', start: '2027-03-01T01:00:00Z', end: null },
      ]),
      [REPORTS]: () => jsonResponse(200, []),
    })
    renderAt('/technical-support/faulty')

    const rows = within(await screen.findByRole('list', { name: 'Blocked units' })).getAllByRole('listitem')
    expect(within(rows[0]).getByRole('img', { name: 'Projector' })).toHaveAttribute('src')
    expect(within(rows[0]).getByRole('link')).toHaveAttribute('href', '/technical-support/inventory/eq-proj')
    expect(rows[0]).toHaveTextContent('until further notice')
    expect(within(rows[1]).getByRole('img', { name: 'No photo of iPad Floor Stand' })).toHaveTextContent('I')
    expect(await screen.findByText('No damaged equipment has been reported.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Report a problem' })).toHaveAttribute('href', '/technical-support/faulty/new')
  })

  it('TS09 TS03 (failure): blocked units that cannot load say so and Try again reloads them', async () => {
    let tries = 0
    stubApi({
      [BLOCKS]: () => (++tries === 1 ? jsonResponse(500, { message: 'Server unavailable' }) : jsonResponse(200, [])),
      [REPORTS]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/faulty')

    expect(await screen.findByRole('alert')).toHaveTextContent('The blocked units could not be loaded. Server unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No unit is marked Faulty or Unavailable.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
