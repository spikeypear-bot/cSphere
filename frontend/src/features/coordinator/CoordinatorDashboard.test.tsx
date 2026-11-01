// @stories EC09 EC02 EC-NEW3  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { CoordinatorHomePage } from './CoordinatorHomePage'

const QUEUE = 'GET /api/event-requests/queue'
const EVENTS = 'GET /api/events/assigned'
const CHANGES = 'GET /api/change-requests'
const ISSUES = 'GET /api/coordinator/operational-issues'

const request = (n: number) => ({
  requestId: `req-${n}`, eventName: n === 1 ? 'Gala' : `Request ${n}`, organisation: 'Acme Pte Ltd',
  startDatetime: '2027-03-12T01:00:00Z', endDatetime: '2027-03-12T04:00:00Z', status: 'SUBMITTED',
})
const event = (n: number) => ({
  eventId: `event-${n}`, eventName: n === 1 ? 'Expo' : `Event ${n}`, organisation: 'Acme Pte Ltd',
  startDatetime: '2027-03-15T01:00:00Z', endDatetime: '2027-03-15T04:00:00Z', status: 'PLANNING',
  journeyStatus: { code: 'PLANNING', label: 'Planning', explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false },
})
const change = { changeRequestId: 'cr-1', requestId: 'req-9', eventId: 'event-1', eventName: 'Expo', organisation: 'Acme Pte Ltd',
  status: 'PENDING', requestedByName: 'eo1', requestedAt: '2027-03-09T01:00:00Z', changes: [] }
const issue = (overlapping: unknown[]) => ({
  issueId: 'iss-1', venueId: 'v-1', venueAddress: '1 Main St', description: 'Lift out of service',
  affectedFrom: null, affectedUntil: null, createdAt: '2027-03-08T01:00:00Z', overlappingEvents: overlapping,
})

function routes(over: Partial<Record<string, () => Response>> = {}) {
  return stubApi({
    [QUEUE]: () => jsonResponse(200, { needsReview: [request(1)], awaitingOrganiser: [] }),
    [EVENTS]: () => jsonResponse(200, [event(1)]),
    [CHANGES]: () => jsonResponse(200, [change]),
    [ISSUES]: () => jsonResponse(200, [issue([{ eventId: 'event-1', eventName: 'Expo', startDatetime: '2027-03-15T01:00:00Z', endDatetime: '2027-03-15T04:00:00Z' }])]),
    ...over,
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/coordinator']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator" element={<CoordinatorHomePage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const findPanel = (title: string) => screen.findByRole('region', { name: title })

describe('EC09 coordinator dashboard', () => {
  beforeEach(() => seedSession('coordinator'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC09 (happy): each panel lists its items and links to them', async () => {
    routes()
    renderDashboard()

    expect(within(await findPanel('Requests to review')).getByRole('link', { name: 'Gala' }))
      .toHaveAttribute('href', '/coordinator/requests/req-1')
    expect(await within(panel('Upcoming events')).findByRole('link', { name: 'Expo' }))
      .toHaveAttribute('href', '/coordinator/events/event-1')
    expect(await within(panel('Change requests waiting')).findByRole('link', { name: /Expo/ }))
      .toHaveAttribute('href', '/coordinator/change-requests/cr-1')
    expect(await within(panel('Venue issues')).findByText(/1 Main St/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, ec1$/)
    expect(await screen.findByText('1 request to review · 1 change waiting')).toBeInTheDocument()
  })

  it('EC09 (boundary): shows at most 5 rows per panel and a View all link', async () => {
    routes({ [QUEUE]: () => jsonResponse(200, { needsReview: [1, 2, 3, 4, 5, 6, 7].map(request), awaitingOrganiser: [] }) })
    renderDashboard()

    await within(await findPanel('Requests to review')).findByRole('link', { name: 'Gala' })
    expect(within(panel('Requests to review')).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all requests to review' })).toHaveAttribute('href', '/coordinator/review-queue')
  })

  it('EC09 (boundary): every panel says so when there is nothing to do', async () => {
    routes({
      [QUEUE]: () => jsonResponse(200, { needsReview: [], awaitingOrganiser: [] }),
      [EVENTS]: () => jsonResponse(200, []),
      [CHANGES]: () => jsonResponse(200, []),
      [ISSUES]: () => jsonResponse(200, []),
    })
    renderDashboard()

    expect(await screen.findByText('No requests waiting for your review.')).toBeInTheDocument()
    expect(await screen.findByText('No events are assigned to you right now.')).toBeInTheDocument()
    expect(await screen.findByText('No change requests waiting.')).toBeInTheDocument()
    expect(await screen.findByText('No venue issues affect your events.')).toBeInTheDocument()
  })

  it('EC09 (failure): one panel failing shows Try again and the others still load', async () => {
    routes({ [EVENTS]: () => jsonResponse(500, { message: 'Server error' }) })
    renderDashboard()

    expect(await within(await findPanel('Upcoming events')).findByRole('alert')).toBeInTheDocument()
    expect(within(panel('Upcoming events')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(await within(panel('Requests to review')).findByRole('link', { name: 'Gala' })).toBeInTheDocument()
  })

  it('EC09 (boundary): a venue issue overlapping no events still shows', async () => {
    routes({ [ISSUES]: () => jsonResponse(200, [issue([])]) })
    renderDashboard()

    expect(await within(await findPanel('Venue issues')).findByText(/1 Main St/)).toBeInTheDocument()
    expect(within(panel('Venue issues')).getByText(/Lift out of service/)).toBeInTheDocument()
  })
})
