// @stories SO-C1  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { SafetyOfficerHomePage } from './SafetyOfficerHomePage'

const PLANNING = 'GET /api/safety/events/planning'
const CONFIRMED = 'GET /api/safety/events/confirmed'

const item = (id: string, eventName: string, status: string, code: string, label: string) => ({
  eventId: id, eventName, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
  venueAddress: '1 Harbour Road', status,
  journeyStatus: { code, label, explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false },
})
const awaiting = (n: number) => item(`aw-${n}`, n === 1 ? 'Sent Gala' : `Sent ${n}`, 'awaiting_safety_review',
  'AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review')
const planning = item('pl-1', 'Breakfast', 'pending', 'PLANNING', 'Planning')
const confirmed = item('co-1', 'Town Hall', 'confirmed', 'CONFIRMED', 'Confirmed')

function routes(over: Partial<Record<string, () => Response>> = {}) {
  return stubApi({
    [PLANNING]: () => jsonResponse(200, [awaiting(1), awaiting(2), planning]),
    [CONFIRMED]: () => jsonResponse(200, [confirmed]),
    ...over,
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/safety-officer']}>
      <SessionProvider>
        <Routes>
          <Route path="/safety-officer" element={<SafetyOfficerHomePage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const findPanel = (title: string) => screen.findByRole('region', { name: title })

describe('SO-C1 safety officer dashboard', () => {
  beforeEach(() => seedSession('safety-officer'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('SO-C1 (happy): each panel lists its events and links to them', async () => {
    routes()
    renderDashboard()

    const review = await findPanel('Awaiting your review')
    expect(await within(review).findByRole('link', { name: 'Sent Gala' })).toHaveAttribute('href', '/safety-officer/events/aw-1')
    expect(within(review).queryByRole('link', { name: 'Breakfast' })).not.toBeInTheDocument()
    expect(within(panel('In planning')).getByRole('link', { name: 'Breakfast' })).toHaveAttribute('href', '/safety-officer/events/pl-1')
    expect(await within(panel('Confirmed')).findByRole('link', { name: 'Town Hall' }))
      .toHaveAttribute('href', '/safety-officer/events/co-1')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, so1$/)
    expect(screen.getByText('2 events awaiting your review')).toBeInTheDocument()
  })

  it('SO-C1 (boundary): shows at most 5 rows per panel and View all links to Safety reviews', async () => {
    routes({ [PLANNING]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6, 7].map(awaiting)) })
    renderDashboard()

    await within(await findPanel('Awaiting your review')).findByRole('link', { name: 'Sent Gala' })
    expect(within(panel('Awaiting your review')).getAllByRole('listitem')).toHaveLength(5)
    for (const name of ['View all events awaiting your review', 'View all events in planning', 'View all confirmed events']) {
      expect(screen.getByRole('link', { name })).toHaveAttribute('href', '/safety-officer/reviews')
    }
  })

  it('SO-C1 (boundary): every panel says so when there is nothing to show', async () => {
    routes({ [PLANNING]: () => jsonResponse(200, []), [CONFIRMED]: () => jsonResponse(200, []) })
    renderDashboard()

    expect(await screen.findByText('No events are waiting for your safety review.')).toBeInTheDocument()
    expect(screen.getByText('No other events are being planned.')).toBeInTheDocument()
    expect(await screen.findByText('No confirmed events to show.')).toBeInTheDocument()
    expect(screen.getByText('0 events awaiting your review')).toBeInTheDocument()
  })

  it('SO-C1 (failure): one panel failing shows Try again and the others still load', async () => {
    routes({ [CONFIRMED]: () => jsonResponse(500, { message: 'Server error' }) })
    renderDashboard()

    expect(await within(await findPanel('Confirmed')).findByRole('alert')).toBeInTheDocument()
    expect(within(panel('Confirmed')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(await within(panel('Awaiting your review')).findByRole('link', { name: 'Sent Gala' })).toBeInTheDocument()
  })
})
