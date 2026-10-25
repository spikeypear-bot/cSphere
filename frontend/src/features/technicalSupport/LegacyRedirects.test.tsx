// @stories TS09 TS01 TS02 TS03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import App from '../../App'
import { jsonResponse, seedSession } from '../../test/apiStubs'

const REQUEST = {
  requestId: 'r-7', eventId: 'event-7', eventName: 'Town Hall',
  eventStart: '2027-03-10T01:00:00Z', eventEnd: '2027-03-10T04:00:00Z', technicalRequirement: '', status: 'processing',
  organisationName: 'Acme Pte Ltd', requestedBy: null, submittedAt: null, isShortfallRequest: false, items: [], eventStatus: 'PLANNING',
}
const REPORT = {
  reportId: 'rep-9', equipmentId: 'eq-proj', equipmentName: 'Projector', serialised: true, serialNumber: 'PJ-1', quantity: 1,
  description: 'Lamp exploded', periodStart: '2027-03-01T00:00:00Z', periodEnd: null, markFaulty: false, faultyMarked: false,
  status: 'OPEN', reportedBy: 't-1', reportedByName: 'ts1', reportedAt: '2027-03-01T00:00:00Z', closedAt: null,
  canClose: false, affectedEvents: [],
}

function Location() {
  const location = useLocation()
  return <output aria-label="location">{location.pathname}{location.search}</output>
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <Location />
    </MemoryRouter>,
  )
}

describe('TS09 the Technical Support routes from before the rework redirect to their new pages', () => {
  beforeEach(() => {
    seedSession('technical-support')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/equipment-requests?status=all') return jsonResponse(200, [REQUEST])
      if (url === '/api/equipment-damage-reports/rep-9') return jsonResponse(200, REPORT)
      return jsonResponse(200, [])
    }))
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it.each([
    ['/technical-support/availability', '/technical-support/requests', 'Equipment requests'],
    ['/technical-support/reservations', '/technical-support/requests', 'Equipment requests'],
    ['/technical-support/availability?request=r-7', '/technical-support/requests/r-7', 'Town Hall'],
    ['/technical-support/reservations?request=r-7&tab=x', '/technical-support/requests/r-7?tab=x', 'Town Hall'],
    ['/technical-support/status', '/technical-support/inventory', 'Inventory'],
    ['/technical-support/status?from=2027-03-01&to=2027-03-02', '/technical-support/inventory?from=2027-03-01&to=2027-03-02', 'Inventory'],
    ['/technical-support/damaged-equipment', '/technical-support/faulty', 'Faulty equipment'],
    ['/technical-support/damaged-equipment/new', '/technical-support/faulty/new', 'Report a problem'],
    ['/technical-support/damaged-equipment/rep-9', '/technical-support/faulty/reports/rep-9', 'Projector · PJ-1'],
  ])('TS09 (cross-cutting): %s opens %s', async (oldPath, newPath, heading) => {
    renderApp(oldPath)

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
    expect(screen.getByLabelText('location')).toHaveTextContent(newPath)
    expect(screen.getByLabelText('location').textContent).toBe(newPath)
  })

  it('TS09 (negative): another role following an old link is still refused before any redirect', async () => {
    seedSession('coordinator')
    renderApp('/technical-support/availability?request=r-7')

    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByLabelText('location').textContent).toMatch(/^\/coordinator\?access-denied=/)
    expect(screen.queryByRole('heading', { name: 'Town Hall' })).not.toBeInTheDocument()
  })
})
