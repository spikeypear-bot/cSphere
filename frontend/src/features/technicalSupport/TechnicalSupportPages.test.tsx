// @stories TS01 TS02 TS03 TS06 TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { requestPageRoutes } from '../../test/requestPageStubs'
import { AppShell } from '../../components/layout/AppShell'
import { technicalSupportNav } from './technicalSupportNav'
import { TechnicalSupportDashboardPage } from './dashboard/TechnicalSupportDashboardPage'
import { EquipmentRequestDetailPage } from './requests/EquipmentRequestDetailPage'
import { FaultyEquipmentPage } from './faulty/FaultyEquipmentPage'
import { ReportProblemPage } from './faulty/ReportProblemPage'
import { EquipmentDetailPage } from './inventory/EquipmentDetailPage'
import { toLocalInputValue } from './reservations/reservationUtils'

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'
const localStart = toLocalInputValue(eventStart)
const localEnd = toLocalInputValue(eventEnd)
// Entered times are Singapore time on every machine (the app reads them so, whatever the browser's timezone).
const localToIso = (value: string) => new Date(`${value}:00+08:00`).toISOString()

function renderRoutes(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <AppShell navLabel="Technical Support" items={technicalSupportNav}>
        <Routes>
          <Route path="/technical-support" element={<TechnicalSupportDashboardPage />} />
          <Route path="/technical-support/requests/:requestId" element={<EquipmentRequestDetailPage />} />
          <Route path="/technical-support/faulty" element={<FaultyEquipmentPage />} />
          <Route path="/technical-support/faulty/new" element={<ReportProblemPage />} />
          <Route path="/technical-support/inventory/:equipmentId" element={<EquipmentDetailPage />} />
        </Routes>
        </AppShell>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('Technical Support UI integration', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS09 (happy): the console navigation links to the requests, faulty equipment and inventory pages', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    renderRoutes('/technical-support')

    const nav = screen.getByRole('navigation', { name: 'Technical Support' })
    expect(within(nav).getByRole('link', { name: 'Equipment requests' })).toHaveAttribute(
      'href', '/technical-support/requests',
    )
    expect(within(nav).getByRole('link', { name: 'Faulty equipment' })).toHaveAttribute(
      'href', '/technical-support/faulty',
    )
    expect(within(nav).getByRole('link', { name: 'Inventory' })).toHaveAttribute(
      'href', '/technical-support/inventory',
    )
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Nothing to handle right now.')).toBeInTheDocument()
  })

  it('TS02 (happy): one button reserves every remaining unit, and the saved reservation is listed for the event', async () => {
    const request = {
      requestId: 'request-1',
      eventId: 'event-1',
      eventName: 'Town Hall',
      eventStart: eventStart,
      eventEnd: eventEnd,
      technicalRequirement: 'Presentation equipment',
      status: 'processing',
    }
    const reservation = {
      logId: 'log-1',
      eventId: 'event-1',
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      quantity: 1,
      serialNumber: null,
      loanedFrom: localToIso(localStart),
      loanedUntil: localToIso(localEnd),
      spare: false,
    }
    const saved: unknown[] = []
    const calls = stubApi({
      ...requestPageRoutes(request, {
        requests: () => [request],
        lines: () => [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 1 }],
        reservations: () => [...saved],
        availability: () => [{
          equipmentId: 'projector-1', equipmentName: 'Projector', serialised: false,
          totalQuantity: 4, availableQuantity: saved.length ? 2 : 3,
        }],
      }),
      'POST /api/equipment/reservations/batch': () => {
        saved.push(reservation)
        return jsonResponse(201, [reservation])
      },
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/requests/request-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()
    const row = await screen.findByRole('row', { name: /Projector/ })
    expect(row).toHaveTextContent('Projector')
    expect(screen.getByLabelText('Reserve now: Projector')).toHaveValue(1)
    const reserve = screen.getByRole('button', { name: 'Reserve all remaining (1 unit)' })
    expect(reserve).toBeEnabled()
    await user.click(reserve)

    const done = await screen.findByText('Reserved 1 unit for this event.')
    expect(done).toHaveAttribute('role', 'status')
    expect(done).toHaveFocus()
    expect(await screen.findByText('✓ Reserved')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Reserved for this event' })).getByText('1 unit')).toBeInTheDocument()
    const post = calls.find(call => call.method === 'POST')
    expect(post?.body).toEqual({
      eventId: 'event-1',
      start: localToIso(localStart),
      end: localToIso(localEnd),
      lines: [{ equipmentId: 'projector-1', quantity: 1, spare: 0 }],
    })
    expect(new Headers(post?.headers).get('Authorization')).toBe('Bearer access-1')
    expect(calls.filter(call => call.method === 'GET').every(
      call => new Headers(call.headers).get('Authorization') === 'Bearer access-1',
    )).toBe(true)
  })

  it('TS06 (failure): shows reservation coverage, keeps the reason when saving a rejection fails, then saves it without changing reservations', async () => {
    const request = {
      requestId: 'request-1',
      eventId: 'event-1',
      eventName: 'Town Hall',
      eventStart,
      eventEnd,
      technicalRequirement: 'Presentation equipment',
      status: 'processing',
    }
    const reservation = {
      logId: 'log-1',
      eventId: 'event-1',
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      quantity: 1,
      serialNumber: null,
      loanedFrom: localToIso(localStart),
      loanedUntil: localToIso(localEnd),
    }
    let decisionAttempts = 0
    const calls = stubApi({
      ...requestPageRoutes(request, {
        requests: () => [request],
        lines: () => [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 2 }],
        reservations: () => [reservation],
        availability: () => [{
          equipmentId: 'projector-1', equipmentName: 'Projector', serialised: false,
          totalQuantity: 4, availableQuantity: 3,
        }],
      }),
      'PATCH /api/equipment-requests/request-1/status': () => {
        decisionAttempts += 1
        if (decisionAttempts === 1) return jsonResponse(409, { message: 'Decision could not be saved.' })
        return jsonResponse(200, {
          requestId: 'request-1',
          eventId: 'event-1',
          status: 'rejected',
          technicalRequirement: request.technicalRequirement,
          rejectReason: 'Only one unit can be provided.',
          lines: [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 2 }],
        })
      },
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/requests/request-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()
    expect(await screen.findByText('1 item still needs reserving')).toBeInTheDocument()
    const row = screen.getByRole('row', { name: /Projector/ })
    expect(within(row).getAllByRole('cell').map((cell) => cell.textContent).slice(0, 3)).toEqual(['2', '1', '3'])
    expect(screen.getByRole('button', { name: 'Approve request' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Reject…' }))
    const reason = screen.getByLabelText('Reason for rejection')
    expect(reason).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Reject request' })).toBeDisabled()
    await user.type(reason, 'Only one unit can be provided.')
    const saveButton = screen.getByRole('button', { name: 'Reject request' })
    await user.click(saveButton)

    expect(await screen.findByRole('alert')).toHaveTextContent('Decision could not be saved.')
    expect(screen.getByLabelText('Reason for rejection')).toHaveValue('Only one unit can be provided.')
    expect(screen.queryByText('This request was rejected.')).not.toBeInTheDocument()
    expect(screen.getByText('Awaiting review')).toBeInTheDocument()
    await user.click(saveButton)

    expect(await screen.findByText('This request was rejected.')).toBeInTheDocument()
    expect(screen.getByText('Reason: Only one unit can be provided.')).toBeInTheDocument()
    expect(screen.getByText('Rejected', { selector: '.dash-badge' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Reserve now: Projector')).not.toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
    const patch = calls.find((call) => call.method === 'PATCH')
    expect(patch?.body).toEqual({
      status: 'rejected',
      rejectReason: 'Only one unit can be provided.',
    })
  })

  it('TS06 (happy): approves the request when all requested equipment is reserved for the event period', async () => {
    const request = {
      requestId: 'request-2',
      eventId: 'event-2',
      eventName: 'Planning Meeting',
      eventStart,
      eventEnd,
      technicalRequirement: '',
      status: 'processing',
    }
    const reservation = {
      logId: 'log-2',
      eventId: 'event-2',
      equipmentId: 'screen-1',
      equipmentName: 'Screen',
      quantity: 2,
      serialNumber: null,
      loanedFrom: localToIso(localStart),
      loanedUntil: localToIso(localEnd),
    }
    const calls = stubApi({
      ...requestPageRoutes(request, {
        requests: () => [request],
        lines: () => [{ equipmentId: 'screen-1', equipmentName: 'Screen', quantity: 2 }],
        reservations: () => [reservation],
        availability: () => [{
          equipmentId: 'screen-1', equipmentName: 'Screen', serialised: false,
          totalQuantity: 4, availableQuantity: 2,
        }],
      }),
      'PATCH /api/equipment-requests/request-2/status': () => jsonResponse(200, {
        requestId: 'request-2',
        eventId: 'event-2',
        status: 'approved',
        technicalRequirement: '',
        rejectReason: null,
        lines: [{ equipmentId: 'screen-1', equipmentName: 'Screen', quantity: 2 }],
      }),
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/requests/request-2')

    expect(await screen.findByRole('heading', { level: 1, name: 'Planning Meeting' })).toBeInTheDocument()
    expect(await screen.findByText('✓ Everything requested is reserved')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nothing to reserve' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Approve request' }))

    expect(await screen.findByText('✓ This request was approved.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve request' })).not.toBeInTheDocument()
    expect(screen.getByText('2 units')).toBeInTheDocument()
    expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
      status: 'approved',
    })
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
  })

  it('TS03 TS09 (happy): marks a unit Faulty, shows the refreshed status, then marks it available again', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 25, 9, 0))
    const unit = { serialNumber: 'P-01', status: 'Available' as 'Available' | 'Faulty', statusPeriods: [] as object[] }
    const block = { id: 'period-1', status: 'Faulty', start: localToIso('2026-09-25T09:00'), end: localToIso('2026-09-25T17:00') }
    // The equipment page's default window: 25 Sep and the next seven days.
    const windowQuery = `from=${encodeURIComponent(new Date(2026, 8, 25).toISOString())}&to=${encodeURIComponent(new Date(2026, 9, 3).toISOString())}`
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [
        { equipmentId: 'projector-1', equipmentName: 'Projector', totalQuantity: 1, serialised: true },
      ]),
      'GET /api/equipment/projector-1': () => jsonResponse(200, {
        equipmentId: 'projector-1', equipmentName: 'Projector', serialised: true, totalQuantity: 1, units: [{ ...unit }],
      }),
      'POST /api/equipment/projector-1/units/P-01/periods': body => {
        const data = body as { status: 'Faulty'; start: string; end: string | null }
        unit.status = data.status
        unit.statusPeriods = [{ ...block, status: data.status, start: data.start, end: data.end }]
        return jsonResponse(204, null)
      },
      'DELETE /api/equipment/periods/period-1': () => {
        unit.status = 'Available'
        unit.statusPeriods = []
        return jsonResponse(204, null)
      },
      'GET /api/equipment/status-periods?current=true': () => jsonResponse(200, unit.statusPeriods.map((p) => ({
        ...p, equipmentId: 'projector-1', equipmentName: 'Projector', serialNumber: 'P-01',
      }))),
      'GET /api/equipment-damage-reports': () => jsonResponse(200, []),
      'GET /api/equipment-damage-reports?equipmentId=projector-1': () => jsonResponse(200, []),
      [`GET /api/equipment/projector-1/reservations?${windowQuery}`]: () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/faulty/new?equipment=projector-1&serial=P-01')

    await screen.findByRole('option', { name: 'P-01 (Available)' })
    await user.selectOptions(screen.getByLabelText('Status'), 'Faulty')
    await user.type(screen.getByLabelText('Starts'), '2026-09-25T09:00')
    await user.type(screen.getByLabelText('Ends (optional)'), '2026-09-25T17:00')
    await user.type(screen.getByLabelText('Description'), 'Lamp flickers')
    await user.click(screen.getByRole('button', { name: 'Report problem' }))
    expect(await screen.findByRole('status')).toHaveTextContent('marked Faulty')
    const blocked = await screen.findByRole('link', { name: 'Projector · P-01' })
    expect(blocked).toHaveAttribute('href', '/technical-support/inventory/projector-1')

    await user.click(blocked)
    expect(await screen.findByText('Faulty now')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove (mark available)' }))
    expect(await screen.findByText('P-01 is available again for that period.')).toBeInTheDocument()
    expect(await screen.findByText('Available now')).toBeInTheDocument()

    const statusUpdates = calls.filter(call => call.method === 'POST' || call.method === 'DELETE')
    expect(statusUpdates.map(call => `${call.method} ${(call.body as { status?: string } | undefined)?.status ?? ''}`.trim()))
      .toEqual(['POST Faulty', 'DELETE'])
    expect(statusUpdates.every(
      call => new Headers(call.headers).get('Authorization') === 'Bearer access-1',
    )).toBe(true)
    vi.useRealTimers()
  })

  it('TS03 TS09 (negative): leaving the form without saving changes nothing', async () => {
    const calls = stubApi({
      'GET /api/equipment/catalogue': () => jsonResponse(200, [
        { equipmentId: 'projector-1', equipmentName: 'Projector', totalQuantity: 1, serialised: true },
      ]),
      'GET /api/equipment/projector-1': () => jsonResponse(200, {
        equipmentId: 'projector-1', equipmentName: 'Projector', serialised: true, totalQuantity: 1,
        units: [{ serialNumber: 'P-01', status: 'Available', statusPeriods: [] }],
      }),
      'GET /api/equipment/status-periods?current=true': () => jsonResponse(200, []),
      'GET /api/equipment-damage-reports': () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/faulty/new?equipment=projector-1&serial=P-01')

    await screen.findByRole('option', { name: 'P-01 (Available)' })
    await user.selectOptions(screen.getByLabelText('Status'), 'Faulty')
    await user.click(screen.getByRole('link', { name: 'Back to faulty equipment' }))

    expect(await screen.findByText('No unit is marked Faulty or Unavailable.')).toBeInTheDocument()
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
})
