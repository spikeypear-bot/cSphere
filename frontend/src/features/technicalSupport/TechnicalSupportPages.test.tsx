import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { TechnicalSupportHomePage } from './TechnicalSupportHomePage'
import { EquipmentReservationPage } from './reservations/EquipmentReservationPage'
import { EquipmentStatusPage } from './status/equipmentStatusPage'
import { toLocalInputValue } from './reservations/reservationUtils'

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'
const localStart = toLocalInputValue(eventStart)
const localEnd = toLocalInputValue(eventEnd)
const localToIso = (value: string) => new Date(value).toISOString()

function renderRoutes(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support" element={<TechnicalSupportHomePage />} />
          <Route path="/technical-support/reservations" element={<EquipmentReservationPage />} />
          <Route path="/technical-support/status" element={<EquipmentStatusPage />} />
        </Routes>
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

  it('links the console to working reservation and status pages', () => {
    renderRoutes('/technical-support')

    expect(screen.getByRole('link', { name: /Reservations/ })).toHaveAttribute(
      'href', '/technical-support/reservations',
    )
    expect(screen.getByRole('link', { name: /Equipment status/ })).toHaveAttribute(
      'href', '/technical-support/status',
    )
    expect(screen.getAllByText('Available')).toHaveLength(2)
  })

  it('selects an equipment request, reserves it, and shows the saved event reservation', async () => {
    const request = {
      requestId: 'request-1',
      eventId: 'event-1',
      eventName: 'Town Hall',
      eventStart: eventStart,
      eventEnd: eventEnd,
      technicalRequirement: 'Presentation equipment',
    }
    const line = { equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 1 }
    const availability = {
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialised: false,
      totalQuantity: 4,
      availableQuantity: 3,
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
    const availabilityUrl = `/api/equipment/requests/request-1/availability?start=${encodeURIComponent(localToIso(localStart))}&end=${encodeURIComponent(localToIso(localEnd))}`
    const routes = {
      'GET /api/equipment-requests/processing': () => jsonResponse(200, [request]),
      'GET /api/equipment-requests/request-1/lines': () => jsonResponse(200, [line]),
      'GET /api/equipment/events/event-1/reservations': () => jsonResponse(200, [reservation]),
      [`GET ${availabilityUrl}`]: () => jsonResponse(200, [availability]),
      'POST /api/equipment/reservations': () => jsonResponse(201, reservation),
    }
    const calls = stubApi(routes)
    const user = userEvent.setup()
    renderRoutes('/technical-support/reservations')

    await user.click(await screen.findByRole('button', { name: /Town Hall/ }))
    expect(await screen.findByText('Projector: 1 needed')).toBeInTheDocument()
    expect(await screen.findByText('Projector: 3 of 4 available')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Equipment'), 'projector-1')
    await user.click(screen.getByRole('button', { name: 'Reserve' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Reserved 1 × Projector')
    expect(screen.getAllByText(/1 × Projector/)).toHaveLength(2)
    const post = calls.find(call => call.method === 'POST')
    expect(post?.body).toMatchObject({
      eventId: 'event-1',
      equipmentId: 'projector-1',
      quantity: 1,
    })
    expect(new Headers(post?.headers).get('Authorization')).toBe('Bearer access-1')
    expect(calls.filter(call => call.method === 'GET').every(
      call => new Headers(call.headers).get('Authorization') === 'Bearer access-1',
    )).toBe(true)
  })

  it('saves faulty and available status changes and displays the refreshed status', async () => {
    const unit = {
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialNumber: 'P-01',
      status: 'Available',
    }
    const viewedStart = '2026-09-25T09:00'
    const viewedEnd = '2026-09-25T17:00'
    const unitsUrl = `/api/equipment/units?start=${encodeURIComponent(localToIso(viewedStart))}&end=${encodeURIComponent(localToIso(viewedEnd))}`
    const periodsUrl = '/api/equipment/projector-1/units/P-01/periods'
    const blocks: { id: string; status: 'Faulty' | 'Unavailable'; start: string; end: string | null }[] = []
    const calls = stubApi({
      [`GET ${unitsUrl}`]: () => jsonResponse(200, [{ ...unit }]),
      [`GET ${periodsUrl}`]: () => jsonResponse(200, [...blocks]),
      'POST /api/equipment/projector-1/units/P-01/periods': body => {
        const data = body as { status: 'Available' | 'Faulty' | 'Unavailable'; start: string; end: string | null }
        blocks.splice(0)
        unit.status = data.status
        if (data.status !== 'Available') {
          blocks.push({ id: `period-${blocks.length + 1}`, status: data.status, start: data.start, end: data.end })
        }
        return jsonResponse(204, null)
      },
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/status')

    await user.click(await screen.findByRole('button', { name: /Projector P-01 — Available/ }))
    await user.selectOptions(screen.getByLabelText('Status'), 'Faulty')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('status')).toHaveTextContent('marked Faulty')
    expect(await screen.findByRole('button', { name: /Projector P-01 — Faulty/ })).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Status'), 'Available')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('status')).toHaveTextContent('marked Available')
    expect(await screen.findByRole('button', { name: /Projector P-01 — Available/ })).toBeInTheDocument()

    const statusUpdates = calls.filter(call => call.method === 'POST')
    expect(statusUpdates.map(call => (call.body as { status: string }).status)).toEqual(['Faulty', 'Available'])
    expect(statusUpdates.every(
      call => new Headers(call.headers).get('Authorization') === 'Bearer access-1',
    )).toBe(true)
  })

  it('does not change the displayed status when the draft is cancelled', async () => {
    const unit = {
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialNumber: 'P-01',
      status: 'Available',
    }
    const viewedStart = '2026-09-25T09:00'
    const viewedEnd = '2026-09-25T17:00'
    const unitsUrl = `/api/equipment/units?start=${encodeURIComponent(localToIso(viewedStart))}&end=${encodeURIComponent(localToIso(viewedEnd))}`
    const calls = stubApi({
      [`GET ${unitsUrl}`]: () => jsonResponse(200, [unit]),
      'GET /api/equipment/projector-1/units/P-01/periods': () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderRoutes('/technical-support/status')

    await user.click(await screen.findByRole('button', { name: /Projector P-01 — Available/ }))
    await user.selectOptions(screen.getByLabelText('Status'), 'Faulty')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByText('Status for the period above:')).toHaveTextContent('Available')
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
})
