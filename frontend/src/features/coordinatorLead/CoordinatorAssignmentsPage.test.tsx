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

describe('CoordinatorAssignmentsPage (ECL-C2 assigned requests, ELC-C5 reassignment)', () => {
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

  it('only ever reads: a card offers its view link and nothing that changes the request', async () => {
    const calls = stubApi({ [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [assigned('r1', 'Offsite')])]) })
    renderPage()

    const card = await cardFor('Offsite')
    expect(within(card).queryAllByRole('button')).toHaveLength(0)
    expect(within(card).getAllByRole('link')).toHaveLength(1)
    expect(within(card).getByRole('link', { name: 'View request: Offsite' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests/r1')
    // Anchored: the Refresh button's own label contains "assignments".
    expect(screen.queryByRole('button', { name: /^(re)?assign/i })).not.toBeInTheDocument()
    // Reads only (the page also reads each coordinator's events since ECL-C7).
    expect(calls.length).toBeGreaterThan(0)
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it.each([
    ['pending'],
    ['clarification_required'],
  ])('selecting a %s request opens its review page, shown as assigned and offering only Reassign', async (status) => {
    const listed = assigned('r1', 'Offsite', { status })
    const calls = stubApi({
      [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [listed])]),
      'GET /api/event-requests/unassigned/r1': () => jsonResponse(200, {
        request: listed.request, missingFields: [], scheduleValid: true, timeline: [],
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('link', { name: 'View request: Offsite' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Offsite' })).toBeInTheDocument()
    expect(screen.getByText(/has been assigned to ec1, who/)).toBeInTheDocument()
    expect(screen.queryByText(/returns to the unassigned list/)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('button', { name: /clarification|reject|approve|assign/i })
      .map((button) => button.textContent)).toEqual(['Reassign'])
    // Reads only (the page also reads each coordinator's events since ECL-C7).
    expect(calls.length).toBeGreaterThan(0)
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
  })

  it('after a reassignment, confirms it and shows the request under its new coordinator with both counts updated', async () => {
    const listed = assigned('r1', 'Offsite')
    let reassigned = false
    const calls = stubApi({
      [ASSIGNED]: () => jsonResponse(200, reassigned
        ? [coordinator('ec1'), coordinator('ec2', [assigned('r1', 'Offsite', { coordinatorId: 'ec-2' })])]
        : [coordinator('ec1', [listed]), coordinator('ec2')]),
      'GET /api/event-requests/unassigned/r1': () => jsonResponse(200, {
        request: listed.request, missingFields: [], scheduleValid: true, timeline: [],
      }),
      'GET /api/event-requests/unassigned/coordinators': () => jsonResponse(200, [
        { userId: 'ec-1', username: 'ec1' }, { userId: 'ec-2', username: 'ec2' },
      ]),
      'POST /api/event-requests/assigned/r1/reassign': () => {
        reassigned = true
        return jsonResponse(200, { ...listed.request, coordinatorId: 'ec-2' })
      },
    })
    const user = userEvent.setup()
    renderPage()

    expect(eventNames(await groupOf('ec1 1 request'))).toEqual(['Offsite'])
    await groupOf('ec2 0 requests')
    await user.click(screen.getByRole('link', { name: 'View request: Offsite' }))
    await user.click(await screen.findByRole('button', { name: 'Reassign' }))
    await user.selectOptions(await screen.findByLabelText('Event Coordinator'), await screen.findByRole('option', { name: 'ec2' }))
    await user.click(screen.getByRole('button', { name: 'Reassign request' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Coordinator assignments' })).toBeInTheDocument()
    expect(screen.getByText('“Offsite” was reassigned to ec2. The organiser has been notified.')).toBeInTheDocument()
    expect(eventNames(await groupOf('ec2 1 request'))).toEqual(['Offsite'])
    expect(eventNames(await groupOf('ec1 0 requests'))).toEqual([])
    expect(within(await cardFor('Offsite')).getByText('Event Coordinator').nextElementSibling).toHaveTextContent('ec2')
    expect(calls.filter((call) => call.method === 'POST')).toEqual([
      expect.objectContaining({ url: '/api/event-requests/assigned/r1/reassign', body: { coordinatorUserId: 'ec-2' } }),
    ])
  })

  it('shows no confirmation when the page is opened on its own', async () => {
    stubApi({ [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [assigned('r1', 'Offsite')])]) })
    renderPage()

    await cardFor('Offsite')
    expect(screen.queryByText(/was reassigned to/)).not.toBeInTheDocument()
  })

  it('the review page opened from a card leads back to the coordinator assignments', async () => {
    const listed = assigned('r1', 'Offsite')
    stubApi({
      [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [listed])]),
      'GET /api/event-requests/unassigned/r1': () => jsonResponse(200, {
        request: listed.request, missingFields: [], scheduleValid: true, timeline: [],
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('link', { name: 'View request: Offsite' }))
    await screen.findByRole('heading', { level: 1, name: 'Offsite' })
    expect(screen.queryByRole('link', { name: 'Back to unassigned requests' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Back to coordinator assignments' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Coordinator assignments' })).toBeInTheDocument()
  })

  it('does not name the coordinator on the card once the request is held by someone else', async () => {
    const listed = assigned('r1', 'Offsite')
    stubApi({
      [ASSIGNED]: () => jsonResponse(200, [coordinator('ec1', [listed])]),
      'GET /api/event-requests/unassigned/r1': () => jsonResponse(200, {
        request: { ...listed.request, coordinatorId: 'ec-2' }, missingFields: [], scheduleValid: true, timeline: [],
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('link', { name: 'View request: Offsite' }))

    expect(await screen.findByText(/has been assigned to an Event Coordinator, who/)).toBeInTheDocument()
    expect(screen.queryByText(/assigned to ec1/)).not.toBeInTheDocument()
  })
})

const sv = (code: string, label: string) => ({ code, label, explanation: '', nextActor: null, stage: 'REQUEST', isFinal: false, datePassed: false })

describe('CoordinatorAssignmentsPage status filter (EC-NEW2)', () => {
  beforeEach(() => seedSession('coordinator-lead', 'ecl-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('filters assigned requests by the shared status and keeps every coordinator heading', async () => {
    stubApi({ [ASSIGNED]: () => jsonResponse(200, [
      coordinator('ec1', [assigned('r1', 'Town Hall', { journeyStatus: sv('UNDER_REVIEW', 'Under Review') })]),
      coordinator('ec2', [assigned('r2', 'Gala', { status: 'clarification_required',
        journeyStatus: sv('CLARIFICATION_REQUIRED', 'Clarification Required') })]),
    ]) })
    const user = userEvent.setup()
    renderPage()

    const filters = await screen.findByRole('group', { name: 'Filter assigned requests by status' })
    await user.click(within(filters).getByRole('button', { name: /Clarification Required/ }))
    expect(screen.getByText('Gala')).toBeInTheDocument()
    expect(screen.queryByText('Town Hall')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /ec1/ })).toBeInTheDocument()
  })
})
