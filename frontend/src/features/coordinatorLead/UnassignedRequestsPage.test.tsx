import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { UnassignedRequestsPage } from './UnassignedRequestsPage'

const QUEUE = 'GET /api/event-requests/unassigned'
const DAY_MS = 24 * 60 * 60 * 1000

/** A request as the unassigned queue returns it: submitted, no coordinator. */
function request(id: string, name: string, overrides: Record<string, unknown> = {}) {
  return {
    requestId: id, eventName: name, organisation: 'Acme Pte Ltd', expectedAttendance: 150,
    startDatetime: '2026-12-01T01:00:00Z', endDatetime: '2026-12-01T09:00:00Z',
    status: 'pending', coordinatorId: null, rejectionReason: null,
    createdAt: '2026-09-20T09:00:00Z', updatedAt: new Date(Date.now() - DAY_MS).toISOString(),
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/coordinator-lead/unassigned-requests']}>
      <SessionProvider>
        <UnassignedRequestsPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** The card (list item) whose heading is the given event name. */
async function cardFor(name: string) {
  const heading = await screen.findByRole('heading', { level: 2, name })
  return heading.closest('li') as HTMLElement
}

describe('UnassignedRequestsPage (ECL-C1 unassigned queue)', () => {
  beforeEach(() => seedSession('coordinator-lead', 'ecl-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it("shows each waiting request's basic event information on its card", async () => {
    stubApi({
      [QUEUE]: () => jsonResponse(200, [
        request('r1', 'Tech Summit', {
          organisation: 'Globex Holdings', expectedAttendance: 300,
          // 01:00Z–09:00Z is 9:00 am–5:00 pm on 12 Nov in Singapore (UTC+8).
          startDatetime: '2026-11-12T01:00:00Z', endDatetime: '2026-11-12T09:00:00Z',
          updatedAt: new Date(Date.now() - 3 * DAY_MS).toISOString(),
        }),
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
    expect(within(card).getByText('Submitted', { selector: 'dt' }).nextElementSibling).toHaveTextContent('3 days ago')
    // Current status, as the shared badge words a pending request.
    expect(within(card).getByText('Submitted', { selector: '.status-badge' })).toBeInTheDocument()
  })

  it('keeps the order the server sends — longest-waiting first — and says how many are waiting', async () => {
    // Neither alphabetical nor newest-first, so any client-side re-sort shows.
    stubApi({
      [QUEUE]: () => jsonResponse(200, [
        request('r1', 'Offsite', { updatedAt: new Date(Date.now() - 5 * DAY_MS).toISOString() }),
        request('r2', 'Annual Gala', { updatedAt: new Date(Date.now() - 2 * DAY_MS).toISOString() }),
        request('r3', 'Town Hall', { updatedAt: new Date(Date.now() - DAY_MS).toISOString() }),
      ]),
    })
    renderPage()

    await cardFor('Offsite')
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent))
      .toEqual(['Offsite', 'Annual Gala', 'Town Hall'])
    expect(screen.getByText('3 requests are waiting for a coordinator.')).toBeInTheDocument()
  })

  it('shows a clear message when there are no unassigned requests', async () => {
    stubApi({ [QUEUE]: () => jsonResponse(200, []) })
    renderPage()

    expect(await screen.findByText(/No unassigned requests right now\./)).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the access-denied message, not an empty queue, when the server refuses the caller', async () => {
    stubApi({
      [QUEUE]: () => jsonResponse(403, { message: 'You do not have permission to perform this action.' }),
    })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to perform this action.')
    // A failed load must not read as "nothing is waiting".
    expect(screen.queryByText(/No unassigned requests right now\./)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('refreshing re-reads the queue: a new submission appears and an assigned request drops off', async () => {
    let reads = 0
    const calls = stubApi({
      [QUEUE]: () => {
        reads += 1
        return jsonResponse(200, reads === 1
          ? [request('r1', 'Offsite'), request('r2', 'Annual Gala')]
          // r1 has since been assigned a coordinator; r3 was just submitted.
          : [request('r2', 'Annual Gala'), request('r3', 'Town Hall')])
      },
    })
    const user = userEvent.setup()
    renderPage()
    await cardFor('Offsite')

    await user.click(screen.getByRole('button', { name: 'Refresh unassigned requests' }))

    await cardFor('Town Hall')
    expect(screen.queryByRole('heading', { name: 'Offsite' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Annual Gala' })).toBeInTheDocument()
    expect(calls.filter((call) => call.url === '/api/event-requests/unassigned')).toHaveLength(2)
  })

  it('is view-only: a card offers no action, and the page only ever reads', async () => {
    const calls = stubApi({ [QUEUE]: () => jsonResponse(200, [request('r1', 'Offsite')]) })
    renderPage()

    const card = await cardFor('Offsite')
    expect(within(card).queryAllByRole('button')).toHaveLength(0)
    expect(within(card).queryAllByRole('link')).toHaveLength(0)
    // Anchored: the Refresh button's own label contains "unassigned".
    expect(screen.queryByRole('button', { name: /^assign/i })).not.toBeInTheDocument()
    expect(calls.map((call) => call.method)).toEqual(['GET'])
  })
})
