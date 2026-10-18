// @stories EC-NEW5  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { ActivityTimeline } from '../../components/ui/ActivityTimeline'
import { EventDetailsPage } from './EventDetailsPage'
import type { ActivityDto } from '../../types/activity'

const EVENT_ID = 'event-1'

function entry(id: string, type: ActivityDto['type'], extra: Partial<ActivityDto> = {}): ActivityDto {
  return { activityId: id, type, actorName: 'ec1', actorRole: 'ec', message: null, flaggedFields: [],
    fromStatus: null, toStatus: null, occurredAt: `2026-09-2${id.slice(-1)}T01:00:00Z`, significance: 'normal', ...extra }
}

const log: ActivityDto[] = [
  entry('a1', 'submitted', { actorName: 'eo1', actorRole: 'eo', fromStatus: 'draft', toStatus: 'pending' }),
  entry('a2', 'venue_booking_rejected', { actorName: 'vs1', actorRole: 'vs', significance: 'major',
    message: 'Rejected Harbour Hall: Closed for repairs' }),
  entry('a3', 'venue_booking_approved', { actorName: 'vs1', actorRole: 'vs', message: 'Approved Marina Hall' }),
  entry('a4', 'equipment_requested', { message: 'Requested 4 items of equipment: Wireless mic × 4' }),
]

function items() {
  return within(screen.getByRole('list', { name: 'Request timeline' })).getAllByRole('listitem')
    .filter((li) => li.classList.contains('activity-timeline__entry'))
}

describe('EC-NEW5 change log — reading a long log', () => {
  afterEach(cleanup)

  it('EC-NEW5 AC2–AC6 (happy): each entry says who, in which role, what they did, why, and when in Singapore time', () => {
    render(<ActivityTimeline entries={log} controls />)

    const rows = items()
    expect(rows).toHaveLength(4)
    expect(rows[1]).toHaveTextContent('vs1 (Venue Staff) rejected a venue booking')
    expect(rows[1]).toHaveTextContent('Closed for repairs')
    expect(rows[2]).toHaveTextContent('vs1 (Venue Staff) approved a venue booking')
    expect(rows[3]).toHaveTextContent('ec1 (Event Coordinator) requested equipment')
    expect(within(rows[0]).getByRole('time')).toHaveAttribute('dateTime', '2026-09-21T01:00:00Z')
    expect(within(rows[0]).getByRole('time')).toHaveTextContent(/ago|in /)
    expect(rows[0]).toHaveTextContent('21 Sept 2026, 09:00 am (Singapore time)')
  })

  it('EC-NEW5 AC17, AC20 (happy): marks Major changes in words, not by colour alone', () => {
    render(<ActivityTimeline entries={log} controls />)

    const [, rejected, approved] = items()
    expect(within(rejected).getByText('Major')).toBeInTheDocument()
    expect(rejected).toHaveAttribute('data-significance', 'major')
    expect(within(approved).queryByText('Major')).not.toBeInTheDocument()
    expect(approved).toHaveAttribute('data-significance', 'normal')
  })

  it('EC-NEW5 AC24, AC25 (happy): filters to Major changes and back, with counts', async () => {
    const user = userEvent.setup()
    render(<ActivityTimeline entries={log} controls />)

    const all = screen.getByRole('button', { name: /All changes 4/ })
    const major = screen.getByRole('button', { name: /Major only 1/ })
    expect(all).toHaveAttribute('aria-pressed', 'true')

    await user.click(major)
    expect(major).toHaveAttribute('aria-pressed', 'true')
    expect(items()).toHaveLength(1)
    expect(items()[0]).toHaveTextContent('rejected a venue booking')

    await user.click(all)
    expect(items()).toHaveLength(4)
  })

  it('EC-NEW5 AC24, AC25 (boundary): with no Major changes, says so and offers to show all', async () => {
    const user = userEvent.setup()
    render(<ActivityTimeline entries={[log[0], log[2]]} controls />)

    await user.click(screen.getByRole('button', { name: /Major only 0/ }))
    expect(screen.getByText(/No major changes recorded/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show all changes' }))
    expect(items()).toHaveLength(2)
  })

  it('EC-NEW5 AC32 (happy): jumps to the latest entry', () => {
    render(<ActivityTimeline entries={log} controls />)

    expect(screen.getByRole('link', { name: 'Jump to latest' })).toHaveAttribute('href', '#latest-change')
    expect(items()[3]).toHaveAttribute('id', 'latest-change')
    expect(items()[0]).not.toHaveAttribute('id')
  })

  it('EC-NEW5 AC33 (boundary): says when earlier changes were not recorded, only for a log without its start', () => {
    const { rerender } = render(<ActivityTimeline entries={log.slice(1)} controls />)
    expect(screen.getByRole('note')).toHaveTextContent('Earlier changes were not recorded')

    rerender(<ActivityTimeline entries={log} controls />)
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('EC-NEW5 AC34 (boundary): an event with no changes says so', () => {
    render(<ActivityTimeline entries={[]} emptyText="No changes recorded yet." controls />)

    expect(screen.getByText('No changes recorded yet.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('EC-NEW5 AC14 (happy): the event taking place is shown without a person or role', () => {
    render(<ActivityTimeline entries={[entry('a9', 'event_completed', { activityId: null, actorName: 'ConnectSphere',
      actorRole: null, toStatus: 'COMPLETED' })]} />)

    const row = screen.getByRole('listitem')
    expect(row).toHaveTextContent('ConnectSphere recorded that the event took place')
    expect(row.querySelector('.activity-timeline__role')).toBeNull()
  })

  it('EC-NEW5 AC10–AC12 (happy): names every kind of venue and equipment change', () => {
    const kinds: [ActivityDto['type'], string][] = [
      ['venue_booking_replaced', 'requested a replacement venue'],
      ['venue_added', 'added another venue'],
      ['venue_alternative_suggested', 'suggested an alternative'],
      ['venue_unavailable', 'marked a booked venue as unavailable'],
      ['equipment_approved', 'approved the equipment request'],
      ['equipment_rejected', 'rejected the equipment request'],
    ]
    render(<ActivityTimeline entries={kinds.map(([type], i) => entry(`k${i}`, type))} />)

    items().forEach((row, i) => expect(row).toHaveTextContent(kinds[i][1]))
  })

  it('EC-NEW5 AC26, AC27 (negative): offers no way to edit, delete or revert an entry', () => {
    render(<ActivityTimeline entries={log} controls />)

    for (const row of items()) {
      expect(within(row).queryAllByRole('button')).toHaveLength(0)
      expect(within(row).queryByRole('textbox')).not.toBeInTheDocument()
    }
  })
})

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

function seedCoordinator() {
  window.localStorage.setItem('connectsphere.auth', JSON.stringify({
    accessToken: 'access-1', refreshToken: 'refresh-1', userId: 'user-1', username: 'ec1', role: 'coordinator',
    organisation: 'ConnectSphere',
  }))
}

const event = {
  eventId: EVENT_ID, eventName: 'Q1 Town Hall', purpose: 'All-hands update', description: null,
  startDatetime: '2026-12-01T09:00:00Z', endDatetime: '2026-12-01T11:00:00Z', expectedAttendance: 150,
  venueId: null, accessibilityNeeds: [], registrationNeeds: null, organisation: 'Acme Conferences',
  venueRequirements: 'Theatre', equipmentRequirements: null, status: 'pending', coordinatorName: 'ec1',
  coordinatorEmail: 'ec1@connectsphere.test',
}

function renderEvent(timeline: () => Response) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === `/api/events/${EVENT_ID}/timeline`) return timeline()
    if (url === `/api/events/${EVENT_ID}`) return jsonResponse(200, event)
    return jsonResponse(404, { message: 'Not stubbed' })
  }))
  seedCoordinator()
  render(
    <MemoryRouter initialEntries={[`/coordinator/events/${EVENT_ID}`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/events/:eventId" element={<EventDetailsPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EC-NEW5 change log on the event page', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC-NEW5 AC1, AC24 (happy): shows the change log on the event, with its tools', async () => {
    renderEvent(() => jsonResponse(200, log))

    const card = await screen.findByRole('region', { name: 'Change log' })
    expect(await within(card).findByRole('list', { name: 'Request timeline' })).toBeInTheDocument()
    expect(within(card).getByRole('group', { name: 'Filter the change log' })).toBeInTheDocument()
  })

  it('EC-NEW5 AC34 (boundary): says no changes are recorded yet', async () => {
    renderEvent(() => jsonResponse(200, []))

    const card = await screen.findByRole('region', { name: 'Change log' })
    expect(await within(card).findByText('No changes recorded yet.')).toBeInTheDocument()
  })

  it('EC-NEW5 AC35 (failure): says the change log could not be loaded, keeps the event, and loads it on Try again', async () => {
    let calls = 0
    renderEvent(() => (++calls === 1 ? jsonResponse(500, { message: 'Server error' }) : jsonResponse(200, log)))
    const user = userEvent.setup()

    const card = await screen.findByRole('region', { name: 'Change log' })
    const problem = await within(card).findByRole('alert')
    expect(problem).toHaveTextContent('The change log could not be loaded.')
    // The rest of the event page still works.
    expect(screen.getByRole('heading', { name: 'Q1 Town Hall' })).toBeInTheDocument()

    await user.click(within(problem).getByRole('button', { name: 'Try again' }))
    expect(await within(card).findByRole('list', { name: 'Request timeline' })).toBeInTheDocument()
    expect(within(card).queryByRole('alert')).not.toBeInTheDocument()
  })
})
