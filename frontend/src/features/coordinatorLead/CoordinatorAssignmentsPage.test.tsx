import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { CoordinatorAssignmentsPage } from './CoordinatorAssignmentsPage'
import { IncomingRequestReviewPage } from './IncomingRequestReviewPage'

const ASSIGNED = 'GET /api/event-requests/assigned'
const DAY_MS = 24 * 60 * 60 * 1000
const daysAgo = (days: number) => new Date(Date.now() - days * DAY_MS).toISOString()

/** An assigned request as the list returns it: the request, and the date it
 * was submitted. `updatedAt` is later, because assigning moved it on. */
function assigned(id: string, name: string, overrides: Record<string, unknown> = {}, submittedDaysAgo = 4) {
  return {
    request: {
      requestId: id, eventName: name, organisation: 'Acme Pte Ltd', expectedAttendance: 150,
      startDatetime: '2026-12-01T01:00:00Z', endDatetime: '2026-12-01T09:00:00Z',
      status: 'pending', coordinatorId: 'ec-1', rejectionReason: null, accessibilityNeeds: [],
      createdAt: daysAgo(9), updatedAt: daysAgo(1),
      ...overrides,
    },
    submittedAt: daysAgo(submittedDaysAgo),
  }
}

function coordinator(name: string, requests: unknown[] = []) {
  return { coordinatorId: `id-${name}`, coordinatorName: name, requests }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/coordinator-lead/assignments']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator-lead/assignments" element={<CoordinatorAssignmentsPage />} />
          <Route path="/coordinator-lead/unassigned-requests/:requestId" element={<IncomingRequestReviewPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** The card (list item) whose heading is the given event name. */
async function cardFor(name: string) {
  const heading = await screen.findByRole('heading', { level: 3, name })
  return heading.closest('li') as HTMLElement
}

/** The section of one coordinator, found by its heading. */
async function groupOf(heading: string) {
  return (await screen.findByRole('heading', { level: 2, name: heading })).closest('section') as HTMLElement
}

/** Event names on the cards inside one element, in page order. */
function eventNames(container: HTMLElement = document.body) {
  return within(container).queryAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
}

describe('CoordinatorAssignmentsPage (ECL-C2 assigned requests)', () => {
  beforeEach(() => seedSession('coordinator-lead', 'ecl-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it("shows each request's event, organisation, dates, attendance, status, date submitted and coordinator", async () => {
    stubApi({
      [ASSIGNED]: () => jsonResponse(200, [
        coordinator('ec1', [
          assigned('r1', 'Tech Summit', {
            organisation: 'Globex Holdings', expectedAttendance: 300, status: 'clarification_required',
            // 01:00Z–09:00Z is 9:00 am–5:00 pm on 12 Nov in Singapore (UTC+8).
            startDatetime: '2026-11-12T01:00:00Z', endDatetime: '2026-11-12T09:00:00Z',
          }, 6),
        ]),
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
    expect(within(card).getByText('Clarification required', { selector: '.status-badge' })).toBeInTheDocument()
    // Submitted six days ago; the request was last updated (assigned) one day ago.
    expect(within(card).getByText('Submitted', { selector: 'dt' }).nextElementSibling).toHaveTextContent('6 days ago')
    expect(within(card).getByText('Event Coordinator').nextElementSibling).toHaveTextContent('ec1')
  })

  it('groups requests under their Event Coordinator, keeping the order the server sends', async () => {
    // Neither alphabetical nor by id, so any client-side re-sort shows.
    stubApi({
      [ASSIGNED]: () => jsonResponse(200, [
        coordinator('ec1', [assigned('r3', 'Offsite'), assigned('r1', 'Annual Gala'), assigned('r2', 'Town Hall')]),
        coordinator('ec2', [assigned('r4', 'Product Launch', { coordinatorId: 'ec-2' })]),
      ]),
    })
    renderPage()

    expect(eventNames(await groupOf('ec1 3 requests'))).toEqual(['Offsite', 'Annual Gala', 'Town Hall'])
    expect(eventNames(await groupOf('ec2 1 request'))).toEqual(['Product Launch'])
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent))
      .toEqual(['ec1 3 requests', 'ec2 1 request'])
    expect(within(await cardFor('Product Launch')).getByText('Event Coordinator').nextElementSibling)
      .toHaveTextContent('ec2')
  })

  it('shows how many requests each coordinator holds, including a coordinator who holds none', async () => {
    stubApi({
      [ASSIGNED]: () => jsonResponse(200, [
        coordinator('ec1', [assigned('r1', 'Offsite'), assigned('r2', 'Annual Gala')]),
        coordinator('ec2'),
        coordinator('ec3', [assigned('r3', 'Town Hall', { coordinatorId: 'ec-3' })]),
      ]),
    })
    renderPage()

    await groupOf('ec1 2 requests')
    expect(eventNames(await groupOf('ec2 0 requests'))).toEqual([])
    await groupOf('ec3 1 request')
    expect(screen.getByText('3 requests are assigned and under review.')).toBeInTheDocument()
    // Something is assigned, so this is not the empty state.
    expect(screen.queryByText(/No assigned requests right now\./)).not.toBeInTheDocument()
  })

  it('shows a clear message when no requests are assigned, with every coordinator holding none', async () => {
    stubApi({ [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1'), coordinator('ec2')]) })
    renderPage()

    expect(await screen.findByText(/No assigned requests right now\./)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'ec1 0 requests' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'ec2 0 requests' })).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the access-denied message and no request data when the server refuses the caller', async () => {
    stubApi({
      [ASSIGNED]: () => jsonResponse(403, { message: 'You do not have permission to perform this action.' }),
    })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to perform this action.')
    // A failed load must not read as "nothing is assigned".
    expect(screen.queryByText(/No assigned requests right now\./)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('heading', { level: 2 })).toHaveLength(0)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('refreshing re-reads the list: a newly assigned request appears and a decided one drops off', async () => {
    let reads = 0
    const calls = stubApi({
      [ASSIGNED]: () => {
        reads += 1
        return jsonResponse(200, reads === 1
          ? [coordinator('ec1', [assigned('r1', 'Offsite'), assigned('r2', 'Annual Gala')]), coordinator('ec2')]
          // r1 has since been approved; r3 was just assigned to ec2.
          : [coordinator('ec1', [assigned('r2', 'Annual Gala')]),
            coordinator('ec2', [assigned('r3', 'Town Hall', { coordinatorId: 'ec-2' })])])
      },
    })
    const user = userEvent.setup()
    renderPage()
    await groupOf('ec1 2 requests')

    await user.click(screen.getByRole('button', { name: 'Refresh coordinator assignments' }))

    expect(eventNames(await groupOf('ec2 1 request'))).toEqual(['Town Hall'])
    expect(eventNames(await groupOf('ec1 1 request'))).toEqual(['Annual Gala'])
    expect(screen.queryByRole('heading', { name: 'Offsite' })).not.toBeInTheDocument()
    expect(calls.filter((call) => call.url === '/api/event-requests/assigned')).toHaveLength(2)
  })

  it('only ever reads: a card offers its review link and nothing that changes the request', async () => {
    const calls = stubApi({ [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [assigned('r1', 'Offsite')])]) })
    renderPage()

    const card = await cardFor('Offsite')
    expect(within(card).queryAllByRole('button')).toHaveLength(0)
    expect(within(card).getAllByRole('link')).toHaveLength(1)
    expect(within(card).getByRole('link', { name: 'Review request: Offsite' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests/r1')
    // Anchored: the Refresh button's own label contains "assignments".
    expect(screen.queryByRole('button', { name: /^(re)?assign/i })).not.toBeInTheDocument()
    expect(calls.map((call) => call.method)).toEqual(['GET'])
  })

  it.each([
    ['pending'],
    ['clarification_required'],
  ])('selecting a %s request opens its review page, shown as assigned with no review actions', async (status) => {
    const listed = assigned('r1', 'Offsite', { status })
    const calls = stubApi({
      [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [listed])]),
      'GET /api/event-requests/unassigned/r1': () => jsonResponse(200, {
        request: listed.request, missingFields: [], scheduleValid: true, timeline: [],
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('link', { name: 'Review request: Offsite' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Offsite' })).toBeInTheDocument()
    expect(screen.getByText(/has been assigned to an Event Coordinator/)).toBeInTheDocument()
    expect(screen.queryByText(/returns to the unassigned list/)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('button', { name: /clarification|reject|approve|assign/i })).toHaveLength(0)
    expect(calls.map((call) => call.method)).toEqual(['GET', 'GET'])
  })
})
