import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { EquipmentReviewPage } from './EquipmentReviewPage'
import { toLocalInputValue } from '../reservations/reservationUtils'

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'
const localStart = toLocalInputValue(eventStart)
const localEnd = toLocalInputValue(eventEnd)
const localToIso = (value: string) => new Date(value).toISOString()

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/technical-support/availability']}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/availability" element={<EquipmentReviewPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

function reviewRoutes(availabilityQuantity: number, statuses: string[]) {
  const request = {
    requestId: 'request-1',
    eventId: 'event-1',
    eventName: 'Town Hall',
    eventStart,
    eventEnd,
    technicalRequirement: 'Presentation equipment',
  }
  const unitsUrl = `/api/equipment/units?start=${encodeURIComponent(localToIso(localStart))}&end=${encodeURIComponent(localToIso(localEnd))}`
  return stubApi({
    'GET /api/equipment-requests/processing': () => jsonResponse(200, [request]),
    'GET /api/equipment-requests/request-1/lines': () => jsonResponse(200, [
      { equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 2 },
    ]),
    [`GET /api/equipment/requests/request-1/availability?start=${encodeURIComponent(localToIso(localStart))}&end=${encodeURIComponent(localToIso(localEnd))}`]:
      () => jsonResponse(200, [{
        equipmentId: 'projector-1',
        equipmentName: 'Projector',
        serialised: true,
        totalQuantity: 2,
        availableQuantity: availabilityQuantity,
      }]),
    [`GET ${unitsUrl}`]: () => jsonResponse(200, statuses.map((status, index) => ({
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialNumber: `P-${index + 1}`,
      status,
    }))),
  })
}

describe('TS01 equipment request review', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('compares itemised requirements with period availability and suitable unit statuses', async () => {
    const calls = reviewRoutes(2, ['Available', 'Available'])
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Town Hall/ }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'This equipment request can be fulfilled for the event period.',
    )
    expect(screen.getByText('Requested')).toBeInTheDocument()
    expect(screen.getByText('2 of 2')).toBeInTheDocument()
    expect(screen.getByText('Suitable units (status Available)')).toBeInTheDocument()
    expect(screen.getByText('P-1')).toBeInTheDocument()
    expect(screen.getAllByText('Available')).toHaveLength(2)
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('rejects a request when enough stock exists but no units are suitable', async () => {
    const calls = reviewRoutes(2, ['Faulty', 'Unavailable'])
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Town Hall/ }))

    expect(await screen.findByText('This equipment request cannot be fully fulfilled as-is.'))
      .toBeInTheDocument()
    const alerts = screen.getAllByRole('alert').map((alert) => alert.textContent).join(' ')
    expect(alerts).toContain('No suitable units are available')
    expect(screen.getByText('Faulty')).toBeInTheDocument()
    expect(screen.getByText('Unavailable')).toBeInTheDocument()
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('flags insufficient quantity even when units have suitable status', async () => {
    const calls = reviewRoutes(1, ['Available', 'Available'])
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Town Hall/ }))

    expect(await screen.findByText('This equipment request cannot be fully fulfilled as-is.'))
      .toBeInTheDocument()
    const alerts = screen.getAllByRole('alert').map((alert) => alert.textContent).join(' ')
    expect(alerts).toContain('Insufficient quantity')
    expect(alerts).not.toContain('No suitable units are available')
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })
})
