import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { AssignedEventsPage } from './AssignedEventsPage'

const MY_EVENTS = 'GET /api/events/assigned'

/** An event as the "assigned to me" list returns it: approved, still being planned. */
function event(id: string, name: string, overrides: Record<string, unknown> = {}) {
  return {
    eventId: id, eventName: name, purpose: 'Quarterly update', description: null,
    startDatetime: '2026-12-01T01:00:00Z', endDatetime: '2026-12-01T09:00:00Z',
    expectedAttendance: 150, venueId: null, accessibilityNeeds: [], requiredFacilities: [],
    registrationNeeds: false, organisation: 'Acme Pte Ltd', venueRequirements: 'Theatre seating',
    equipmentRequirements: null, status: 'pending', coordinatorName: 'ec1', coordinatorEmail: null,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/coordinator/my-events']}>
      <SessionProvider>
        <AssignedEventsPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** The card (list item) whose heading is the given event name. */
async function cardFor(name: string) {
  const heading = await screen.findByRole('heading', { level: 2, name })
  return heading.closest('li') as HTMLElement
}

describe('AssignedEventsPage (EC09 my events)', () => {
  beforeEach(() => seedSession('coordinator', 'ec-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it("shows each event's name, organisation, start and end, expected attendance and current status", async () => {
    stubApi({
      [MY_EVENTS]: () => jsonResponse(200, [
        event('e1', 'Tech Summit', {
          organisation: 'Globex Holdings', expectedAttendance: 300, status: 'confirmed',
          // 01:00Z–09:00Z is 9:00 am–5:00 pm on 12 Nov in Singapore (UTC+8).
          startDatetime: '2026-11-12T01:00:00Z', endDatetime: '2026-11-12T09:00:00Z',
        }),
        event('e2', 'Town Hall'),
      ]),
    })
    renderPage()

    const card = await cardFor('Tech Summit')
    expect(within(card).getByText('Globex Holdings')).toBeInTheDocument()
    const when = within(card).getByText('When (Singapore time)').nextElementSibling
    expect(when).toHaveTextContent(/12 Nov 2026/)
    expect(when).toHaveTextContent(/0?9:00/)
    expect(when).toHaveTextContent(/0?5:00/)
    expect(within(card).getByText('Expected attendance').nextElementSibling).toHaveTextContent('300 people')
    expect(within(card).getByText('Confirmed', { selector: '.status-badge' })).toBeInTheDocument()
    // A pending event is in planning, worded as on the event's own page.
    expect(within(await cardFor('Town Hall')).getByText('Planning', { selector: '.status-badge' })).toBeInTheDocument()
  })

  it('keeps the order the server sends — soonest first — and says how many are assigned', async () => {
    // Neither alphabetical nor reverse-alphabetical, so any client-side re-sort shows.
    stubApi({
      [MY_EVENTS]: () => jsonResponse(200, [
        event('e1', 'Offsite', { startDatetime: '2026-11-02T01:00:00Z', endDatetime: '2026-11-02T03:00:00Z' }),
        event('e2', 'Annual Gala', { startDatetime: '2026-11-20T01:00:00Z', endDatetime: '2026-11-20T03:00:00Z' }),
        event('e3', 'Town Hall', { startDatetime: '2026-12-05T01:00:00Z', endDatetime: '2026-12-05T03:00:00Z' }),
      ]),
    })
    renderPage()

    await cardFor('Offsite')
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent))
      .toEqual(['Offsite', 'Annual Gala', 'Town Hall'])
    expect(screen.getByText('3 events are assigned to you.')).toBeInTheDocument()
  })

  it("links each event to that event's details page", async () => {
    stubApi({ [MY_EVENTS]: () => jsonResponse(200, [event('e1', 'Offsite'), event('e2', 'Annual Gala')]) })
    renderPage()

    expect(within(await cardFor('Offsite')).getByRole('link', { name: 'Open event Offsite' }))
      .toHaveAttribute('href', '/coordinator/events/e1')
    expect(within(await cardFor('Annual Gala')).getByRole('link', { name: 'Open event Annual Gala' }))
      .toHaveAttribute('href', '/coordinator/events/e2')
  })

  it('shows a clear message when no events are assigned to the coordinator', async () => {
    stubApi({ [MY_EVENTS]: () => jsonResponse(200, []) })
    renderPage()

    expect(await screen.findByText(/No events are assigned to you right now\./)).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the access-denied message, not an empty list, when the server refuses the caller', async () => {
    stubApi({
      [MY_EVENTS]: () => jsonResponse(403, { message: 'You do not have permission to perform this action.' }),
    })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to perform this action.')
    // A failed load must not read as "nothing is assigned".
    expect(screen.queryByText(/No events are assigned to you right now\./)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('refreshing re-reads the list: a newly assigned event appears and a reassigned one drops off', async () => {
    let reads = 0
    const calls = stubApi({
      [MY_EVENTS]: () => {
        reads += 1
        return jsonResponse(200, reads === 1
          ? [event('e1', 'Offsite'), event('e2', 'Annual Gala')]
          // e1 has since gone to another coordinator; e3 was just approved.
          : [event('e2', 'Annual Gala'), event('e3', 'Town Hall')])
      },
    })
    const user = userEvent.setup()
    renderPage()
    await cardFor('Offsite')

    await user.click(screen.getByRole('button', { name: 'Refresh my events' }))

    await cardFor('Town Hall')
    expect(screen.queryByRole('heading', { name: 'Offsite' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Annual Gala' })).toBeInTheDocument()
    expect(calls.filter((call) => call.url === '/api/events/assigned')).toHaveLength(2)
  })

  it('only ever reads, and never names a coordinator in the request', async () => {
    const calls = stubApi({ [MY_EVENTS]: () => jsonResponse(200, [event('e1', 'Offsite')]) })
    renderPage()

    const card = await cardFor('Offsite')
    expect(within(card).queryAllByRole('button')).toHaveLength(0)
    // The exact URL, no query string, and no body: whose list it is comes from the token alone.
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual(['GET /api/events/assigned'])
    expect(calls[0].body).toBeUndefined()
  })
})
