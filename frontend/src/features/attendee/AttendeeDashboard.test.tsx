// @stories AT04  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { AUTH_STORAGE_KEY } from '../../lib/authTokens'
import { jsonResponse, stubApi } from '../../test/apiStubs'
import { AttendeeHomePage } from './AttendeeHomePage'

const REGISTRATIONS = 'GET /api/attendee/registrations'
const EVENTS = 'GET /api/attendee/events'

const registration = (n: number, over: Record<string, unknown> = {}) => ({
  registrationId: `reg-${n}`, reference: `CS-${n}`, registeredAt: '2026-10-01T00:00:00Z', withdrawnAt: null,
  registrationStatus: 'confirmed', eventId: `event-${n}`, eventName: n === 1 ? 'Gala' : `Registered ${n}`,
  startDatetime: '2099-03-12T01:00:00Z', endDatetime: '2099-03-12T04:00:00Z', eventStatus: 'confirmed',
  eventDetailsAvailable: true, withdrawalAvailable: true, withdrawalReason: '', ...over,
})
const event = (n: number, over: Record<string, unknown> = {}) => ({
  eventId: `event-${n}`, eventName: n === 1 ? 'Expo' : `Event ${n}`, status: 'confirmed',
  startDatetime: '2099-03-15T01:00:00Z', endDatetime: '2099-03-15T04:00:00Z', registrationRequired: true,
  registrationOpensAt: '2020-01-01T00:00:00Z', registrationClosesAt: '2099-03-01T00:00:00Z',
  registrationCapacity: 10, availablePlaces: 3, ...over,
})

function routes(over: Partial<Record<string, () => Response>> = {}) {
  return stubApi({
    [REGISTRATIONS]: () => jsonResponse(200, [registration(1)]),
    [EVENTS]: () => jsonResponse(200, [event(1)]),
    ...over,
  })
}

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/attendee']}>
      <SessionProvider>
        <Routes><Route path="/attendee" element={<AttendeeHomePage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

const panel = (title: string) => screen.getByRole('region', { name: title })
const findPanel = (title: string) => screen.findByRole('region', { name: title })

describe('AT04 attendee dashboard', () => {
  beforeEach(() => window.localStorage.setItem(AUTH_STORAGE_KEY,
    JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', username: 'att1', role: 'attendee', organisation: null })))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('AT04 (happy): each panel lists its items and links to them', async () => {
    routes()
    renderDashboard()

    expect(await within(await findPanel('My upcoming registrations')).findByRole('link', { name: 'Gala' }))
      .toHaveAttribute('href', '/attendee/my-registrations/reg-1')
    expect(await within(panel('Open for registration')).findByRole('link', { name: 'Expo' }))
      .toHaveAttribute('href', '/attendee/events/event-1')
    expect(within(panel('Open for registration')).getByText('3 places left')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Good \w+, att1$/)
    expect(await screen.findByText('1 upcoming registration · 1 event open for registration')).toBeInTheDocument()
  })

  it('AT04 (boundary): shows at most 5 rows per panel and a View all link', async () => {
    routes({
      [REGISTRATIONS]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6, 7].map((n) => registration(n))),
      [EVENTS]: () => jsonResponse(200, [1, 2, 3, 4, 5, 6].map((n) => event(n))),
    })
    renderDashboard()

    await within(await findPanel('My upcoming registrations')).findByRole('link', { name: 'Gala' })
    expect(within(panel('My upcoming registrations')).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all my registrations' })).toHaveAttribute('href', '/attendee/my-registrations')
    await within(panel('Open for registration')).findByRole('link', { name: 'Expo' })
    expect(within(panel('Open for registration')).getAllByRole('listitem')).toHaveLength(5)
    expect(screen.getByRole('link', { name: 'View all events' })).toHaveAttribute('href', '/attendee/browse')
    expect(screen.getByText('7 upcoming registrations · 6 events open for registration')).toBeInTheDocument()
  })

  it('AT04 (boundary): withdrawn, past, full, closed and not-yet-open items are left out', async () => {
    routes({
      [REGISTRATIONS]: () => jsonResponse(200, [
        registration(1, { registrationStatus: 'withdrawn' }),
        registration(2, { startDatetime: '2020-01-01T01:00:00Z', endDatetime: '2020-01-01T04:00:00Z', eventStatus: 'completed' }),
        registration(3, { registrationStatus: 'event_cancelled', eventStatus: 'cancelled' }),
      ]),
      [EVENTS]: () => jsonResponse(200, [
        event(1, { availablePlaces: 0 }),
        event(2, { registrationClosesAt: '2020-02-01T00:00:00Z' }),
        event(3, { registrationOpensAt: '2099-01-01T00:00:00Z' }),
        event(4, { registrationRequired: false }),
      ]),
    })
    renderDashboard()

    expect(await screen.findByText('You have no upcoming registrations.')).toBeInTheDocument()
    expect(await screen.findByText('No events are open for registration right now.')).toBeInTheDocument()
    expect(screen.getByText('0 upcoming registrations · 0 events open for registration')).toBeInTheDocument()
  })

  it('AT04 (boundary): a registration whose event awaits re-confirmation says so', async () => {
    routes({ [REGISTRATIONS]: () => jsonResponse(200, [registration(1, { eventStatus: 'reconfirming' })]) })
    renderDashboard()

    await within(await findPanel('My upcoming registrations')).findByRole('link', { name: 'Gala' })
    expect(within(panel('My upcoming registrations')).getByText('Awaiting Re-confirmation')).toBeInTheDocument()
  })

  it('AT04 (failure): one panel failing shows Try again and the other still loads', async () => {
    routes({ [REGISTRATIONS]: () => jsonResponse(500, { message: 'Server error' }) })
    renderDashboard()

    expect(await within(await findPanel('My upcoming registrations')).findByRole('alert')).toBeInTheDocument()
    expect(within(panel('My upcoming registrations')).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(await within(panel('Open for registration')).findByRole('link', { name: 'Expo' })).toBeInTheDocument()
  })

  it('AT04 (boundary): a registration for a cancelled event is not upcoming', async () => {
    routes({ [REGISTRATIONS]: () => jsonResponse(200, [registration(1), registration(2, { eventStatus: 'cancelled' })]) })
    renderDashboard()

    const mine = await findPanel('My upcoming registrations')
    expect(await within(mine).findByRole('link', { name: 'Gala' })).toBeInTheDocument()
    expect(within(mine).queryByRole('link', { name: 'Registered 2' })).not.toBeInTheDocument()
  })
})
