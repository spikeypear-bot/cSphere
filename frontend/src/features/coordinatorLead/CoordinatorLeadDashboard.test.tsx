// @stories ECL-C1  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { CoordinatorLeadHomePage } from './CoordinatorLeadHomePage'

const UNASSIGNED = 'GET /api/event-requests/unassigned'
const REASSIGNMENTS = 'GET /api/event-reassignment-requests'
const ACTIVE = 'GET /api/events/by-coordinator'

const request = (n: number) => ({
  requestId: `req-${n}`, eventName: n === 1 ? 'Gala' : `Request ${n}`, organisation: 'Acme Pte Ltd',
  startDatetime: '2027-03-12T01:00:00Z', endDatetime: '2027-03-12T04:00:00Z', status: 'pending',
  updatedAt: '2027-03-01T01:00:00Z',
})
const reassignment = {
  reassignmentRequestId: 'rr-1', eventId: 'event-1', eventName: 'Expo', organisation: 'Acme Pte Ltd',
  eventStartDatetime: '2027-03-15T01:00:00Z', eventEndDatetime: '2027-03-15T04:00:00Z',
  requesterUserId: 'ec-1', requesterName: 'ec1', reason: 'On leave', status: 'pending',
  requestedAt: '2027-03-09T01:00:00Z', decidedAt: null, decisionReason: null, coordinatorUserId: 'ec-1', coordinatorName: 'ec1',
}
const event = (n: number, start = `2027-04-${String(10 + n).padStart(2, '0')}T01:00:00Z`) => ({
  requestId: null,
  event: {
    eventId: `event-${n}`, eventName: n === 1 ? 'Expo' : `Event ${n}`, organisation: 'Acme Pte Ltd',
    startDatetime: start, endDatetime: start.replace('T01', 'T04'), status: 'pending', expectedAttendance: 100,
    journeyStatus: { code: 'PLANNING', label: 'Planning', explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false },
  },
})

function routes(over: Partial<Record<string, () => Response>> = {}) {
  return stubApi({
    [UNASSIGNED]: () => jsonResponse(200, [request(1)]),
    [REASSIGNMENTS]: () => jsonResponse(200, [reassignment]),
    [ACTIVE]: () => jsonResponse(200, [{ coordinatorId: 'ec-1', coordinatorName: 'ec1', events: [event(1)] }]),
    ...over,
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/coordinator-lead']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator-lead" element={<CoordinatorLeadHomePage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const findPanel = (title: string) => screen.findByRole('region', { name: title })

describe('ECL-C1 Event Coordinator Lead dashboard', () => {
  beforeEach(() => seedSession('coordinator-lead'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('ECL-C1 (happy): each panel lists its items and links to them', async () => {
    routes()
    renderDashboard()

    expect(await within(await findPanel('Unassigned requests')).findByRole('link', { name: 'Gala' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests/req-1')
    expect(await within(panel('Reassignment requests')).findByRole('link', { name: 'Expo' }))
      .toHaveAttribute('href', '/coordinator-lead/reassignment-requests')
    expect(await within(panel('Active events')).findByRole('link', { name: 'Expo' }))
      .toHaveAttribute('href', '/coordinator-lead/events/event-1')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ecl1$/)
    expect(await screen.findByText('1 unassigned request · 1 reassignment waiting')).toBeInTheDocument()
  })

  it('ECL-C1 (boundary): shows at most 5 rows per panel, soonest active event first, and View all links', async () => {
    routes({
      [UNASSIGNED]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6, 7].map(request)),
      [ACTIVE]: () => jsonResponse(200, [
        { coordinatorId: 'ec-1', coordinatorName: 'ec1', events: [2, 3, 4].map((n) => event(n)) },
        { coordinatorId: 'ec-2', coordinatorName: 'ec2', events: [event(1, '2027-04-01T01:00:00Z'), event(5), event(6)] },
      ]),
    })
    renderDashboard()

    await within(await findPanel('Unassigned requests')).findByRole('link', { name: 'Gala' })
    expect(within(panel('Unassigned requests')).getAllByRole('listitem')).toHaveLength(5)
    const active = within(await within(panel('Active events')).findByRole('list')).getAllByRole('link')
    expect(active).toHaveLength(5)
    expect(active[0]).toHaveAccessibleName('Expo')
    expect(screen.getByRole('link', { name: 'View all unassigned requests' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests')
    expect(screen.getByRole('link', { name: 'View all reassignment requests' }))
      .toHaveAttribute('href', '/coordinator-lead/reassignment-requests')
    expect(screen.getByRole('link', { name: 'View all active events' }))
      .toHaveAttribute('href', '/coordinator-lead/active-events')
  })

  it('ECL-C1 (boundary): every panel says so when there is nothing to do', async () => {
    routes({
      [UNASSIGNED]: () => jsonResponse(200, []),
      [REASSIGNMENTS]: () => jsonResponse(200, []),
      [ACTIVE]: () => jsonResponse(200, [{ coordinatorId: 'ec-1', coordinatorName: 'ec1', events: [] }]),
    })
    renderDashboard()

    expect(await screen.findByText('No unassigned requests right now.')).toBeInTheDocument()
    expect(await screen.findByText('No reassignment requests waiting.')).toBeInTheDocument()
    expect(await screen.findByText('No active events right now.')).toBeInTheDocument()
    expect(await screen.findByText('0 unassigned requests · 0 reassignments waiting')).toBeInTheDocument()
  })

  it('ECL-C1 (failure): one panel failing shows Try again and the others still load', async () => {
    routes({ [ACTIVE]: () => jsonResponse(500, { message: 'Server error' }) })
    renderDashboard()

    expect(await within(await findPanel('Active events')).findByRole('alert')).toBeInTheDocument()
    expect(within(panel('Active events')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(await within(panel('Unassigned requests')).findByRole('link', { name: 'Gala' })).toBeInTheDocument()
    expect(await within(panel('Reassignment requests')).findByRole('link', { name: 'Expo' })).toBeInTheDocument()
  })
})
