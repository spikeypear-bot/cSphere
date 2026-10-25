// @stories TS01 TS09 TS02  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { seedSession, stubApi } from '../../../test/apiStubs'
import { requestPageRoutes } from '../../../test/requestPageStubs'
import { EquipmentRequestDetailPage } from '../requests/EquipmentRequestDetailPage'

const eventStart = '2027-03-10T09:00:00+08:00'
const eventEnd = '2027-03-10T12:00:00+08:00'

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/technical-support/requests/request-1']}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/requests/:requestId" element={<EquipmentRequestDetailPage />} />
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
    status: 'processing',
  }
  // TS01 runs on the request's own page, in the same table as TS02 (D33).
  return stubApi(requestPageRoutes(request, {
    requests: () => [request],
    lines: () => [{ equipmentId: 'projector-1', equipmentName: 'Projector', quantity: 2 }],
    reservations: () => [],
    availability: () => [{
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialised: true,
      totalQuantity: 2,
      availableQuantity: availabilityQuantity,
    }],
    units: () => statuses.map((status, index) => ({
      equipmentId: 'projector-1',
      equipmentName: 'Projector',
      serialNumber: `P-${index + 1}`,
      status,
    })),
  }))
}

/** Requested, Reserved, Available, in that order. */
async function figures() {
  const row = await screen.findByRole('row', { name: /Projector/ })
  return within(row).getAllByRole('cell').slice(0, 3).map((cell) => cell.textContent)
}

describe('TS01 equipment request review', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS01 (happy): compares itemised requirements with period availability and suitable unit statuses', async () => {
    const calls = reviewRoutes(2, ['Available', 'Available'])
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()

    expect(await figures()).toEqual(['2', '0', '2'])
    expect(screen.getByRole('columnheader', { name: 'Requested' })).toBeInTheDocument()
    expect(screen.getByLabelText('Reserve now: Projector')).toHaveValue(2)
    expect(within(screen.getByRole('list', { name: 'Units to reserve: Projector' })).getAllByRole('listitem')
      .map((chip) => chip.textContent)).toEqual(['P-1auto', 'P-2auto'])
    expect(screen.queryByText(/Short|None free/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Units/ }))
    expect(screen.getByText('P-1', { selector: '.fulfil-unit__serial' })).toBeInTheDocument()
    expect(screen.getAllByText('Free')).toHaveLength(2)
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('TS01 (negative): flags a request when enough stock exists but no units are suitable', async () => {
    const calls = reviewRoutes(2, ['Faulty', 'Unavailable'])
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()

    expect(await screen.findByText('None free for this window: all 2 still needed are short.'))
      .toBeInTheDocument()
    expect(screen.getByLabelText('Reserve now: Projector')).toHaveValue(0)
    expect(screen.queryByRole('list', { name: 'Units to reserve: Projector' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Units/ }))
    expect(screen.getByText('Faulty')).toBeInTheDocument()
    expect(screen.getByText('Unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nothing to reserve' })).toBeDisabled()
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('TS01 (boundary): flags insufficient quantity even when units have suitable status', async () => {
    const calls = reviewRoutes(1, ['Available', 'Available'])
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()

    expect(await screen.findByText('Short: only 1 of the 2 still needed are free for this window.'))
      .toBeInTheDocument()
    expect(await figures()).toEqual(['2', '0', '1'])
    expect(screen.queryByText(/None free/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reserve all remaining (1 unit)' })).toBeEnabled()
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })
})
