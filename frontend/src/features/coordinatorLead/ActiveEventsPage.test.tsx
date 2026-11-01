// @stories ECL-C4 TS09 EC09 AT04 EO15 ECL-C1 VS02
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import App from '../../App'
import { AUTH_STORAGE_KEY, type Role } from '../../lib/authTokens'
import { SessionProvider } from '../../lib/session'
import { emptyBody, jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { JourneyCode } from '../../types/status'
import { ActiveEventsPage } from './ActiveEventsPage'

const OVERVIEW = 'GET /api/events/by-coordinator'

const LABELS: Partial<Record<JourneyCode, string>> = {
  PLANNING: 'Planning',
  AWAITING_SAFETY_REVIEW: 'Awaiting Safety Review',
  SAFETY_CHANGES_REQUESTED: 'Safety Changes Requested',
  SAFETY_APPROVED: 'Safety Approved',
  CONFIRMED: 'Confirmed',
}

/** One active event as the overview returns it. */
function event(id: string, name: string, coordinatorName: string, overrides: {
  code?: JourneyCode; datePassed?: boolean; organisation?: string; start?: string; end?: string; attendance?: number
} = {}) {
  const code = overrides.code ?? 'PLANNING'
  return {
    requestId: `req-${id}`,
    event: {
      eventId: id, eventName: name, purpose: 'Update', description: null,
      startDatetime: overrides.start ?? '2027-03-10T01:00:00Z', endDatetime: overrides.end ?? '2027-03-10T04:00:00Z',
      expectedAttendance: overrides.attendance ?? 120, venueId: null, accessibilityNeeds: [], registrationNeeds: false,
      organisation: overrides.organisation ?? 'Acme Pte Ltd', venueRequirements: 'Theatre', equipmentRequirements: null,
      status: 'pending', coordinatorName, coordinatorEmail: `${coordinatorName}@connectsphere.test`,
      journeyStatus: {
        code, label: LABELS[code] ?? code, explanation: '', nextActor: null, stage: 'EVENT', isFinal: false,
        datePassed: overrides.datePassed ?? false,
      },
    },
  }
}

function coordinator(name: string, events: unknown[] = []) {
  return { coordinatorId: `id-${name}`, coordinatorName: name, events }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/coordinator-lead/active-events']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator-lead/active-events" element={<ActiveEventsPage />} />
          <Route path="/coordinator-lead/events/:eventId" element={<h1>Event oversight page</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function cardFor(name: string) {
  return (await screen.findByRole('heading', { level: 3, name })).closest('li') as HTMLElement
}

function eventNames(container: HTMLElement = document.body) {
  return within(container).queryAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
}

function signInAs(role: Role, username: string) {
  window.localStorage.setItem(AUTH_STORAGE_KEY,
    JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', username, role, organisation: null }))
}

describe('ECL-C4 ActiveEventsPage — the Lead sees every assigned active event', () => {
  beforeEach(() => seedSession('coordinator-lead', 'ecl-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it.each([
    ['coordinator', 'ec1', /^Good \w+, ec1$/, 'Event Coordinator'],
    ['organiser', 'eo1', /^Good \w+, eo1$/, 'Event Organiser'],
    ['venue-staff', 'vs1', /^Good \w+, vs1$/, 'Venue Staff'],
    ['technical-support', 'ts1', /^Good \w+, ts1$/, 'Technical Support'],
    ['attendee', 'att1', /^Good \w+, att1$/, 'Attendee'],
    ['safety-officer', 'so1', /^Good \w+, so1$/, 'Safety Officer'],
  ] as const)('ECL-C4 AC1 (negative): a signed-in %s opening the URL directly is sent to their own console, told why, and no events are read', async (role, username, ownConsole, ownNav) => {
    window.localStorage.clear()
    const fetched: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      fetched.push(String(input))
      return jsonResponse(200, emptyBody(String(input)))
    }))
    signInAs(role, username)
    render(<MemoryRouter initialEntries={['/coordinator-lead/active-events']}><App /></MemoryRouter>)

    expect(await screen.findByRole('heading', { level: 1, name: ownConsole })).toBeInTheDocument()
    // The user's own console, not just any dashboard with their name on it.
    expect(screen.getByRole('navigation', { name: ownNav })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: 'Active events' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.getByRole('alert')).toHaveTextContent('/coordinator-lead/active-events')
    expect(fetched).not.toContain('/api/events/by-coordinator')
  })

  it('ECL-C4 AC1 (happy): the Lead console links to Active events, which opens for the Lead', async () => {
    window.localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, [])))
    signInAs('coordinator-lead', 'ecl1')
    const user = userEvent.setup()
    render(<MemoryRouter initialEntries={['/coordinator-lead']}><App /></MemoryRouter>)

    const nav = await screen.findByRole('navigation', { name: 'Event Coordinator Lead' })
    const link = within(nav).getByRole('link', { name: 'Active events' })
    expect(link).toHaveAttribute('href', '/coordinator-lead/active-events')
    await user.click(link)

    expect(await screen.findByRole('heading', { level: 1, name: 'Active events' })).toBeInTheDocument()
  })

  it("ECL-C4 AC4 (happy): each event shows its name, organiser's organisation, start and end, attendance, status and coordinator", async () => {
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [
        coordinator('ec2', [event('e1', 'Tech Summit', 'ec2', {
          code: 'AWAITING_SAFETY_REVIEW', organisation: 'Globex Holdings', attendance: 300,
          // 01:00Z–09:00Z is 9:00 am–5:00 pm on 12 Nov in Singapore (UTC+8).
          start: '2026-11-12T01:00:00Z', end: '2026-11-12T09:00:00Z',
        })]),
      ]),
    })
    renderPage()

    const card = await cardFor('Tech Summit')
    expect(within(card).getByText('Globex Holdings')).toBeInTheDocument()
    const when = within(card).getByText('When (Singapore time)').nextElementSibling
    expect(when).toHaveTextContent(/12 Nov 2026/)
    expect(when).toHaveTextContent(/0?9:00\s*am/i)
    expect(when).toHaveTextContent(/0?5:00\s*pm/i)
    expect(within(card).getByText('Expected attendance').nextElementSibling).toHaveTextContent('300 people')
    expect(within(card).getByText('Awaiting Safety Review')).toBeInTheDocument()
    expect(within(card).getByText('Event Coordinator').nextElementSibling).toHaveTextContent('ec2')
  })

  it('ECL-C4 AC5 (happy): filtering by coordinator shows only that coordinator\'s events', async () => {
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [
        coordinator('ec1', [event('e1', 'Offsite', 'ec1')]),
        coordinator('ec2', [event('e2', 'Gala', 'ec2'), event('e3', 'Fair', 'ec2', { code: 'CONFIRMED' })]),
      ]),
    })
    const user = userEvent.setup()
    renderPage()
    await cardFor('Offsite')

    await user.selectOptions(screen.getByLabelText('Event Coordinator'), 'ec2')

    expect(eventNames()).toEqual(['Gala', 'Fair'])
    expect(screen.queryByRole('heading', { level: 2, name: /^ec1/ })).not.toBeInTheDocument()
  })

  it('ECL-C4 AC5 (happy): filtering by status shows only events in that status, in every coordinator', async () => {
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [
        coordinator('ec1', [event('e1', 'Offsite', 'ec1'), event('e2', 'Launch', 'ec1', { code: 'CONFIRMED' })]),
        coordinator('ec2', [event('e3', 'Gala', 'ec2', { code: 'CONFIRMED' }), event('e4', 'Fair', 'ec2', { code: 'SAFETY_APPROVED' })]),
      ]),
    })
    const user = userEvent.setup()
    renderPage()
    await cardFor('Offsite')

    const filter = screen.getByRole('group', { name: 'Filter active events by status' })
    await user.click(within(filter).getByRole('button', { name: /^Confirmed/ }))

    expect(eventNames()).toEqual(['Launch', 'Gala'])
    // The workload count is still each coordinator's whole load.
    expect(screen.getByRole('heading', { level: 2, name: 'ec1 2 active events' })).toBeInTheDocument()
  })

  it('ECL-C4 AC5 (boundary): "All events" lists every coordinator\'s events in one list, soonest start first', async () => {
    // Each group is in server order; across groups the dates interleave, and
    // two events are one minute apart.
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [
        coordinator('ec1', [
          event('e1', 'Second', 'ec1', { start: '2027-01-10T01:01:00Z', end: '2027-01-10T03:00:00Z' }),
          event('e2', 'Fourth', 'ec1', { start: '2027-05-01T01:00:00Z', end: '2027-05-01T03:00:00Z' }),
        ]),
        coordinator('ec2', [
          event('e3', 'First', 'ec2', { start: '2027-01-10T01:00:00Z', end: '2027-01-10T03:00:00Z' }),
          event('e4', 'Third', 'ec2', { start: '2027-02-01T01:00:00Z', end: '2027-02-01T03:00:00Z' }),
        ]),
      ]),
    })
    const user = userEvent.setup()
    renderPage()
    await cardFor('First')
    expect(eventNames()).toEqual(['Second', 'Fourth', 'First', 'Third'])

    await user.click(screen.getByRole('button', { name: 'All events' }))

    expect(eventNames(screen.getByRole('list', { name: 'All active events, soonest first' })))
      .toEqual(['First', 'Second', 'Third', 'Fourth'])
    expect(within(await cardFor('Second')).getByText('Event Coordinator').nextElementSibling).toHaveTextContent('ec1')
  })

  it('ECL-C4 AC6 (boundary): shows each coordinator\'s active event count, including coordinators with none', async () => {
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [
        coordinator('ec1', [event('e1', 'Offsite', 'ec1'), event('e2', 'Gala', 'ec1')]),
        coordinator('ec2'),
        coordinator('ec3', [event('e3', 'Fair', 'ec3')]),
      ]),
    })
    renderPage()

    expect(await screen.findByRole('heading', { level: 2, name: 'ec1 2 active events' })).toBeInTheDocument()
    const none = screen.getByRole('heading', { level: 2, name: 'ec2 0 active events' }).closest('section') as HTMLElement
    expect(eventNames(none)).toEqual([])
    expect(screen.getByRole('heading', { level: 2, name: 'ec3 1 active event' })).toBeInTheDocument()
    expect(screen.queryByText(/No active events right now/)).not.toBeInTheDocument()
  })

  it('ECL-C4 AC7 (negative): an event can only be opened for viewing — no approve, book, confirm or reassign actions', async () => {
    stubApi({ [OVERVIEW]: () => jsonResponse(200, [coordinator('ec1', [event('e1', 'Offsite', 'ec1', { code: 'SAFETY_APPROVED' })])]) })
    const user = userEvent.setup()
    renderPage()

    const card = await cardFor('Offsite')
    // The whole card is the one link, named by the event.
    expect(within(card).getAllByRole('link')).toHaveLength(1)
    expect(within(card).getByRole('link', { name: 'Offsite' })).toHaveAttribute('href', '/coordinator-lead/events/e1')
    expect(within(card).queryAllByRole('button')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /approve|book|confirm|reassign/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /approve|book|confirm|reassign/i })).not.toBeInTheDocument()

    await user.click(within(card).getByRole('link', { name: 'Offsite' }))
    expect(await screen.findByRole('heading', { name: 'Event oversight page' })).toBeInTheDocument()
  })

  it('ECL-C4 AC8 (conflict): refreshing reads the assignments again and shows a reassigned event under its new coordinator', async () => {
    let reads = 0
    const calls = stubApi({
      [OVERVIEW]: () => {
        reads += 1
        return jsonResponse(200, reads === 1
          ? [coordinator('ec1', [event('e1', 'Offsite', 'ec1')]), coordinator('ec2')]
          : [coordinator('ec1'), coordinator('ec2', [event('e1', 'Offsite', 'ec2')])])
      },
    })
    const user = userEvent.setup()
    renderPage()
    expect(await screen.findByRole('heading', { level: 2, name: 'ec1 1 active event' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Refresh active events' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'ec2 1 active event' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'ec1 0 active events' })).toBeInTheDocument()
    expect(within(await cardFor('Offsite')).getByText('Event Coordinator').nextElementSibling).toHaveTextContent('ec2')
    expect(calls.filter((call) => call.method === 'GET')).toHaveLength(2)
  })

  it('ECL-C4 AC9 (boundary): an event whose date passed without confirmation is flagged; an upcoming one is not', async () => {
    stubApi({
      [OVERVIEW]: () => jsonResponse(200, [coordinator('ec1', [
        event('e1', 'Overdue Fair', 'ec1', { code: 'AWAITING_SAFETY_REVIEW', datePassed: true,
          start: '2020-01-15T01:00:00Z', end: '2020-01-15T04:00:00Z' }),
        event('e2', 'Upcoming Fair', 'ec1', { code: 'AWAITING_SAFETY_REVIEW' }),
      ])]),
    })
    renderPage()

    expect(within(await cardFor('Overdue Fair')).getByText('Date passed')).toBeInTheDocument()
    expect(within(await cardFor('Upcoming Fair')).queryByText('Date passed')).not.toBeInTheDocument()
  })

  it('ECL-C4 AC10 (negative): shows a clear message when there are no active events', async () => {
    stubApi({ [OVERVIEW]: () => jsonResponse(200, [coordinator('ec1'), coordinator('ec2')]) })
    renderPage()

    expect(await screen.findByText(/No active events right now\./)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'ec1 0 active events' })).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('ECL-C4 AC10 (failure): a loading error is shown with Retry, which reads again, and viewing never writes anything', async () => {
    let reads = 0
    const calls = stubApi({
      [OVERVIEW]: () => {
        reads += 1
        return reads === 1
          ? jsonResponse(500, { message: 'The server is unavailable.' })
          : jsonResponse(200, [coordinator('ec1', [event('e1', 'Offsite', 'ec1')])])
      },
    })
    const user = userEvent.setup()
    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load active events.')
    expect(screen.queryByText(/No active events right now/)).not.toBeInTheDocument()

    await user.click(within(alert).getByRole('button', { name: 'Retry' }))

    expect(await cardFor('Offsite')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(calls).toHaveLength(2)
    expect(calls.every((call) => call.method === 'GET' && call.url === '/api/events/by-coordinator')).toBe(true)
  })
})
