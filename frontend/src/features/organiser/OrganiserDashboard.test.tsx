// @stories EO15  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { OrganiserHomePage } from './OrganiserHomePage'

const REQUESTS = 'GET /api/event-requests'
const CHANGES = 'GET /api/change-requests'

const journey = (code: string, isFinal = false) =>
  ({ code, label: code, explanation: '', nextActor: null, stage: 'EVENT', isFinal, datePassed: false })

const request = (n: number, over: Record<string, unknown> = {}) => ({
  requestId: `req-${n}`, eventId: null, eventName: `Request ${n}`, status: 'draft',
  startDatetime: '2027-03-12T01:00:00Z', endDatetime: '2027-03-12T04:00:00Z',
  createdAt: '2027-03-01T01:00:00Z', updatedAt: '2027-03-01T01:00:00Z', organisation: 'Acme Pte Ltd',
  journeyStatus: null, ...over,
})
const asked = request(1, { eventName: 'Gala', status: 'clarification_required', journeyStatus: journey('CLARIFICATION_REQUIRED') })
const draft = request(2, { eventName: null })
const planned = request(3, { eventName: 'Expo', status: 'approved', eventId: 'event-3', journeyStatus: journey('PLANNING'),
  startDatetime: '2027-04-01T01:00:00Z', endDatetime: '2027-04-01T04:00:00Z' })
const sooner = request(4, { eventName: 'Launch', status: 'approved', eventId: 'event-4', journeyStatus: journey('CONFIRMED') })
const finished = request(5, { eventName: 'Old Fair', status: 'approved', eventId: 'event-5', journeyStatus: journey('COMPLETED', true) })
const change = (status: string) => ({ changeRequestId: `cr-${status}`, requestId: 'req-3', eventId: 'event-3',
  eventName: 'Expo', organisation: 'Acme Pte Ltd', status, requestedByName: 'eo1', requestedAt: '2027-03-09T01:00:00Z', changes: [] })

function routes(over: Partial<Record<string, () => Response>> = {}) {
  return stubApi({
    [REQUESTS]: () => jsonResponse(200, [asked, draft, planned, sooner, finished]),
    [CHANGES]: () => jsonResponse(200, [change('pending'), change('approved')]),
    ...over,
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/organiser']}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser" element={<OrganiserHomePage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const findPanel = (title: string) => screen.findByRole('region', { name: title })

describe('EO15 organiser dashboard', () => {
  beforeEach(() => seedSession('organiser'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EO15 (happy): each panel lists its items and links to the page that acts on them', async () => {
    routes()
    renderDashboard()

    const action = await findPanel('Needs your action')
    expect(await within(action).findByRole('link', { name: /Gala/ })).toHaveAttribute('href', '/organiser/requests/req-1/respond')
    expect(within(action).getByRole('link', { name: /Untitled draft/ })).toHaveAttribute('href', '/organiser/requests/req-2')

    const events = panel('Upcoming events')
    expect(within(events).getByRole('link', { name: /Expo/ })).toHaveAttribute('href', '/organiser/events/event-3')
    // Soonest first, and a finished event is not "upcoming".
    expect(within(within(events).getByRole('list')).getAllByRole('link').map((link) => link.getAttribute('href')))
      .toEqual(['/organiser/events/event-4', '/organiser/events/event-3'])

    const changes = panel('Change requests waiting')
    expect(within(await within(changes).findByRole('list')).getAllByRole('link')).toHaveLength(1)
    expect(within(changes).getByRole('link', { name: /Expo/ })).toHaveAttribute('href', '/organiser/requests/req-3/changes')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, eo1$/)
    expect(await screen.findByText('2 requests need your action · 1 change waiting')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'New event request' })).toHaveAttribute('href', '/organiser/requests/new')
  })

  it('EO15 (boundary): shows at most 5 rows per panel and a View all link to My requests', async () => {
    routes({ [REQUESTS]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6, 7].map((n) => request(n))) })
    renderDashboard()

    await within(await findPanel('Needs your action')).findByRole('link', { name: /Request 1/ })
    expect(within(panel('Needs your action')).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all requests needing your action' })).toHaveAttribute('href', '/organiser/requests')
    expect(screen.getByRole('link', { name: 'View all my events' })).toHaveAttribute('href', '/organiser/requests')
    expect(screen.getByRole('link', { name: 'View all change requests' })).toHaveAttribute('href', '/organiser/requests')
  })

  it('EO15 (boundary): every panel says so when there is nothing to do', async () => {
    routes({ [REQUESTS]: () => jsonResponse(200, []), [CHANGES]: () => jsonResponse(200, [change('approved')]) })
    renderDashboard()

    expect(await screen.findByText('Nothing needs your action right now.')).toBeInTheDocument()
    expect(await screen.findByText('No upcoming events yet.')).toBeInTheDocument()
    expect(await screen.findByText('No change requests waiting.')).toBeInTheDocument()
  })

  it('EO15 (failure): one panel failing shows Try again and the others still load', async () => {
    routes({ [CHANGES]: () => jsonResponse(500, { message: 'Server error' }) })
    renderDashboard()

    expect(await within(await findPanel('Change requests waiting')).findByRole('alert')).toBeInTheDocument()
    expect(within(panel('Change requests waiting')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(await within(panel('Needs your action')).findByRole('link', { name: /Gala/ })).toBeInTheDocument()
    expect(await screen.findByText('2 requests need your action')).toBeInTheDocument()
  })

  it('EO15 (boundary): a change that can no longer be decided is not shown as waiting', async () => {
    routes({ [CHANGES]: () => jsonResponse(200, [{ ...change('pending'), closedReason: 'The event has started.' }]) })
    renderDashboard()

    expect(await within(await findPanel('Change requests waiting')).findByText('No change requests waiting.')).toBeInTheDocument()
    expect(await screen.findByText(/0 changes waiting/)).toBeInTheDocument()
  })

  it('EO15 (boundary): an approved event that has already ended is not upcoming', async () => {
    const ended = request(6, { eventName: 'Past Fair', status: 'approved', eventId: 'event-6', journeyStatus: journey('CONFIRMED'),
      startDatetime: '2020-01-01T01:00:00Z', endDatetime: '2020-01-01T04:00:00Z' })
    routes({ [REQUESTS]: () => jsonResponse(200, [planned, ended]) })
    renderDashboard()

    expect(await within(await findPanel('Upcoming events')).findByRole('link', { name: 'Expo' })).toBeInTheDocument()
    expect(within(panel('Upcoming events')).queryByRole('link', { name: 'Past Fair' })).not.toBeInTheDocument()
  })
})
