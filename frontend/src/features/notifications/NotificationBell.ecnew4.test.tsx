// @stories EC-NEW4 EO-C1 EC-C4 EC-C1 EO03
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { NotificationBell } from './NotificationBell'

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

const BASE = {
  eventRequestId: 'req-1', eventId: null, eventName: 'Town Hall', newStatus: null, reason: null,
  coordinatorName: null, coordinatorEmail: null, isReassignment: false, read: false, message: null,
  actorName: null, actorRole: null, linkPath: '/coordinator/requests/req-1',
}

const ASSIGNED = {
  ...BASE, notificationId: 'a1', type: 'request_assigned', occurredAt: '2026-10-28T02:00:00Z',
  message: 'Assigned to you: Acme Pte Ltd, 10 Mar 2027, 9:00 AM to 10 Mar 2027, 12:00 PM (Singapore time), 150 expected.',
  actorName: 'ecl1', actorRole: 'ecl',
}

const HANDED_OVER = {
  ...BASE, notificationId: 'h1', type: 'request_handed_over', occurredAt: '2026-10-27T02:00:00Z', read: true,
  message: 'Reassigned to ec2. You no longer coordinate it, and will not be told about it any more.',
  actorName: 'ecl1', actorRole: 'ecl',
}

type Handler = (url: string, init?: RequestInit) => Response | undefined

/** Serves the list and count; `extra` answers anything else, or the test fails on it. */
function stubApi(list: unknown[], count: number, extra?: Handler) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const handled = extra?.(url, init)
    if (handled) return handled
    if (url === '/api/notifications') return jsonResponse(200, list)
    if (url === '/api/notifications/unread-count') return jsonResponse(200, { count })
    throw new Error(`Unexpected fetch: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function listCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter(([url]) => String(url) === '/api/notifications').length
}

function renderBell() {
  return render(
    <MemoryRouter initialEntries={['/coordinator']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator" element={<NotificationBell />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EC-NEW4 NotificationBell — what a coordinator is told, and how', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'a', refreshToken: 'r', userId: 'ec-1', username: 'ec1', role: 'ec', organisation: null,
    }))
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EC-NEW4 AC23 (boundary): the badge never shows more than "99+", while the label keeps the exact number', async () => {
    stubApi([ASSIGNED], 120)
    renderBell()

    expect(await screen.findByLabelText('Notifications, 120 unread')).toBeInTheDocument()
    expect(screen.getByText('99+')).toBeInTheDocument()
    expect(screen.queryByText('120')).not.toBeInTheDocument()
  })

  it('EC-NEW4 AC23 (boundary): exactly 99 unread still shows the number', async () => {
    stubApi([ASSIGNED], 99)
    renderBell()

    expect(await screen.findByText('99')).toBeInTheDocument()
  })

  it('EC-NEW4 AC2, AC4, AC17, AC18, AC24 (happy): each notice says what happened in words, who did it in which role, when (Singapore time), and which are unread', async () => {
    stubApi([ASSIGNED, HANDED_OVER], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('"Town Hall" was assigned to you')).toBeInTheDocument()
    expect(screen.getByText(ASSIGNED.message)).toBeInTheDocument()
    expect(screen.getByText('"Town Hall" is no longer assigned to you')).toBeInTheDocument()
    expect(screen.getAllByText('By ecl1 (Event Coordinator Lead)')).toHaveLength(2)
    expect(screen.getByText(/28 Oct 2026, 10:00\s*am \(Singapore time\)/i)).toBeInTheDocument()
    // Unread is said in text, not only by colour: one unread, one read.
    expect(screen.getAllByText('Unread')).toHaveLength(1)
  })

  it('EC-NEW4 AC3 (happy): a transfer says so', async () => {
    stubApi([{ ...ASSIGNED, isReassignment: true, message: 'Transferred to you from ec1: Acme Pte Ltd' }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('"Town Hall" was transferred to you')).toBeInTheDocument()
  })

  it('EC-NEW4 AC11 (happy): an equipment decision says whether it was approved or rejected, with the reason', async () => {
    stubApi([{ ...BASE, notificationId: 'e1', type: 'equipment_request_decided', occurredAt: '2026-10-28T02:00:00Z',
      reason: 'All mics are booked', message: 'Technical Support rejected your equipment request: All mics are booked',
      actorName: 'ts1', actorRole: 'technician' }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('Equipment request for "Town Hall" was rejected')).toBeInTheDocument()
    expect(screen.getByText('Technical Support rejected your equipment request: All mics are booked')).toBeInTheDocument()
    expect(screen.getByText('By ts1 (Technical Support)')).toBeInTheDocument()
  })

  it('EC-NEW4 AC17 (boundary): a notice the system raised on its own names nobody', async () => {
    stubApi([{ ...ASSIGNED, actorName: null, actorRole: null }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    await screen.findByText('"Town Hall" was assigned to you')
    expect(screen.queryByText(/^By /)).not.toBeInTheDocument()
  })

  it('EC-NEW4 AC25, AC26 (happy): Mark all as read marks up to the newest notice shown, and clears the badge', async () => {
    const posted: unknown[] = []
    // Like the server: once marked, the list and count say so.
    const fetchMock = stubApi([HANDED_OVER, ASSIGNED], 1, (url, init) => {
      if (url === '/api/notifications/read-all' && init?.method === 'POST') {
        posted.push(JSON.parse(String(init.body)))
        return jsonResponse(200, { marked: 1 })
      }
      if (posted.length && url === '/api/notifications') return jsonResponse(200, [HANDED_OVER, { ...ASSIGNED, read: true }])
      if (posted.length && url === '/api/notifications/unread-count') return jsonResponse(200, { count: 0 })
      return undefined
    })
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    await user.click(await screen.findByRole('button', { name: 'Mark all as read' }))

    // The newest is ASSIGNED, even though the list was given oldest first.
    await waitFor(() => expect(posted).toEqual([{ upTo: '2026-10-28T02:00:00Z' }]))
    await waitFor(() => expect(screen.queryByText('Unread')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Notifications')).toBeInTheDocument()
    // It re-syncs with the server afterwards rather than trusting its own guess.
    await waitFor(() => expect(listCalls(fetchMock)).toBeGreaterThanOrEqual(2))
  })

  it('EC-NEW4 AC25 (negative): with nothing unread there is no Mark all as read', async () => {
    stubApi([{ ...ASSIGNED, read: true }], 0)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications'))

    await screen.findByText('"Town Hall" was assigned to you')
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).not.toBeInTheDocument()
  })

  it('EC-NEW4 AC32 (failure): when notifications cannot be loaded the bell says so, and Try again recovers', async () => {
    let failing = true
    stubApi([ASSIGNED], 1, (url) => (failing && url === '/api/notifications' ? jsonResponse(500, { message: 'Down' }) : undefined))
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Notifications could not be loaded.')
    failing = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('"Town Hall" was assigned to you')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('EO-C1 AC6, AC7, AC15 (happy): an organiser sees the whole venue update for their confirmed event, and it opens the event', async () => {
    const message = 'A venue for your event is unavailable from 10 Mar 2027, 8:00 AM to 10 Mar 2027, 8:45 AM (Singapore time). '
      + "Affected venue: 1 Harbour Road. Reason: Flooding. Your event's arrangements are being re-confirmed."
    stubApi([{ ...BASE, notificationId: 'v1', type: 'confirmed_venue_changed', eventId: 'event-1', occurredAt: '2026-10-28T02:00:00Z',
      message, actorName: 'vs1', actorRole: 'vs', linkPath: '/organiser/events/event-1' }], 1,
    (url) => (url === '/api/notifications/v1/read' ? jsonResponse(200, {}) : undefined))
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/coordinator']}>
        <SessionProvider>
          <Routes>
            <Route path="/coordinator" element={<NotificationBell />} />
            <Route path="/organiser/events/:eventId" element={<div>Confirmed arrangements page</div>} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('Venue update for your confirmed event "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText(message)).toBeInTheDocument()
    expect(screen.getByText('By vs1 (Venue Staff)')).toBeInTheDocument()
    await user.click(screen.getByText('Venue update for your confirmed event "Town Hall"'))
    expect(await screen.findByText('Confirmed arrangements page')).toBeInTheDocument()
  })

  it('EC-NEW4 AC3, AC17 / EC-C1 AC6 (happy): a handover notice names the event in its title and shows who it moved between', async () => {
    stubApi([{ ...BASE, notificationId: 'r1', type: 'event_reassignment_approved', eventId: 'event-1', occurredAt: '2026-10-28T02:00:00Z',
      message: 'Transferred to you from ec1. You are now coordinating this event.', actorName: 'ecl1', actorRole: 'ecl' }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('"Town Hall" has been reassigned')).toBeInTheDocument()
    expect(screen.getByText('Transferred to you from ec1. You are now coordinating this event.')).toBeInTheDocument()
  })

  it("EC-C1 AC5 (negative): a declined handover names the event and gives the Lead's reason", async () => {
    stubApi([{ ...BASE, notificationId: 'r2', type: 'event_reassignment_declined', eventId: 'event-1', occurredAt: '2026-10-28T02:00:00Z',
      message: 'Request declined: No other coordinator is free.' }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('Reassignment declined for "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText('Request declined: No other coordinator is free.')).toBeInTheDocument()
  })

  it('EC-C4 AC6 (happy): an unavailability notice gives the reason Venue Staff recorded', async () => {
    stubApi([{ ...BASE, notificationId: 'u1', type: 'venue_unavailable', eventId: 'event-1', occurredAt: '2026-10-28T02:00:00Z',
      reason: 'Flooding in the basement',
      message: 'Affected booking at Riverside Hall (2027-11-16 09:00 to 2027-11-16 12:00, Singapore time). Alternative venue arrangements required. The original booking and event have not been cancelled.' }], 1)
    const user = userEvent.setup()
    renderBell()
    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('Reason: Flooding in the basement')).toBeInTheDocument()
  })

  it('EC-NEW4 AC22 (happy): coming back to the tab fetches at once, without waiting for the next poll', async () => {
    const fetchMock = stubApi([ASSIGNED], 1)
    renderBell()
    await screen.findByLabelText('Notifications, 1 unread')
    const before = listCalls(fetchMock)

    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    await waitFor(() => expect(listCalls(fetchMock)).toBe(before + 1))
  })
})

describe('EO03 NotificationBell — the organiser is told a change request was decided', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EO03 AC37 (happy): the title names the event and the outcome with its reason is shown once, underneath', async () => {
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'a', refreshToken: 'r', userId: 'eo-1', username: 'eo1', role: 'eo', organisation: 'Acme Pte Ltd',
    }))
    const outcome = 'Your change request was rejected. Reason: No venue holds 400 people.'
    stubApi([{ ...BASE, notificationId: 'd1', type: 'change_request_decided', occurredAt: '2026-10-28T02:00:00Z',
      message: outcome, actorName: 'ec1', actorRole: 'ec', linkPath: '/change-requests/req-1' }], 1)
    const user = userEvent.setup()
    renderBell()

    await user.click(await screen.findByRole('button', { name: /^Notifications/ }))
    expect(await screen.findByText('Decision on your change request for "Town Hall"')).toBeInTheDocument()
    // Shown once, as plain text: not quoted like a person's words.
    expect(screen.getAllByText(outcome)).toHaveLength(1)
  })
})

describe('EC-NEW4 NotificationBell — every kind of notice reads as words', () => {
  beforeEach(() => {
    window.localStorage.setItem('connectsphere.auth', JSON.stringify({
      accessToken: 'a', refreshToken: 'r', userId: 'ec-1', username: 'ec1', role: 'ec', organisation: null,
    }))
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  async function openWith(list: unknown[], count: number) {
    const fetchMock = stubApi(list, count, (url) => (url.endsWith('/read') ? jsonResponse(200, {}) : undefined))
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/coordinator']}>
        <SessionProvider>
          <Routes>
            <Route path="/coordinator" element={<><NotificationBell /><p>Outside the bell</p></>} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /^Notifications/ }))
    return { user, fetchMock }
  }

  const notice = (type: string, extra: Record<string, unknown> = {}) => ({
    ...BASE, notificationId: `${type}-${JSON.stringify(extra)}`, type, occurredAt: '2026-10-28T02:00:00Z', read: true, ...extra,
  })

  it.each([
    ['request_submitted', {}, 'New request "Town Hall" is waiting to be assigned'],
    ['equipment_request_decided', { reason: null }, 'Equipment request for "Town Hall" was approved'],
    ['venue_hold_expired', {}, 'Tentative venue hold expired for "Town Hall"'],
    ['event_reassignment_requested', {}, 'Reassignment requested for "Town Hall"'],
    ['change_request_submitted', {}, 'Change requested for "Town Hall"'],
    ['change_request_withdrawn', {}, 'Change request withdrawn for "Town Hall"'],
    ['equipment_request_attention', {}, 'The equipment request for "Town Hall" needs attention'],
    ['venue_timing_changed', {}, 'Venue timings updated for "Town Hall"'],
    ['venue_timing_conflict', {}, 'Venue timing conflict for "Town Hall"'],
    ['event_confirmed', {}, '"Town Hall" is confirmed'],
    ['event_returned_to_planning', {}, '"Town Hall" is back in Planning'],
    ['event_safety_approved', {}, '"Town Hall" passed its safety check and is ready to confirm'],
    ['clarification_requested', {}, 'Clarification needed on "Town Hall"'],
    ['clarification_responded', {}, '"Town Hall" was updated and resubmitted'],
    ['venue_booking_rejected', {}, 'Venue booking for "Town Hall" was rejected'],
    ['venue_booking_approved', {}, 'Venue booking for "Town Hall" was approved'],
    ['venue_booking_requested', {}, 'New venue booking request for "Town Hall"'],
    ['venue_booking_cancelled', {}, 'Venue booking request for "Town Hall" was cancelled'],
    ['coordinator_assignment', { coordinatorName: 'ec2' }, '"Town Hall" assigned to ec2'],
    ['coordinator_assignment', { isReassignment: true, coordinatorName: null }, '"Town Hall" reassigned to a coordinator'],
    ['status_change', { newStatus: 'cancelled' }, '"Town Hall" is now Cancelled'],
    ['status_change', { newStatus: 'on_hold' }, '"Town Hall" is now on_hold'],
    ['status_change', { newStatus: null }, '"Town Hall" is now updated'],
  ])('EC-NEW4 (happy): a %s notice %j is titled in words', async (type, extra, title) => {
    await openWith([notice(type, extra)], 0)
    expect(await screen.findByText(title)).toBeInTheDocument()
  })

  it('EC-NEW4 (boundary): minutes and hours ago are shown as such, and an unknown role is named as given', async () => {
    const minutesAgo = new Date(Date.now() - 5 * 60_000).toISOString()
    const hoursAgo = new Date(Date.now() - 3 * 3_600_000).toISOString()
    await openWith([
      notice('event_confirmed', { occurredAt: minutesAgo, actorName: 'sys1', actorRole: 'auditor' }),
      notice('venue_timing_changed', { occurredAt: hoursAgo, actorName: 'vs1', actorRole: null }),
    ], 0)
    expect(await screen.findByText('5m ago')).toBeInTheDocument()
    expect(screen.getByText('3h ago')).toBeInTheDocument()
    expect(screen.getByText('By sys1 (auditor)')).toBeInTheDocument()
    expect(screen.getByText('By vs1')).toBeInTheDocument()
  })

  it('EC-NEW4 (happy): opening a read notice with no link closes the list without marking it again or leaving the page', async () => {
    const { user, fetchMock } = await openWith([notice('event_confirmed', { linkPath: null })], 0)
    await user.click(await screen.findByText('"Town Hall" is confirmed'))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.getByText('Outside the bell')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/read'))).toBe(false)
  })

  it('EC-NEW4 (happy): clicking inside the open list keeps it open, clicking outside closes it', async () => {
    const { user } = await openWith([notice('event_confirmed')], 0)
    await user.click(await screen.findByRole('heading', { name: 'Notifications' }))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    await user.click(screen.getByText('Outside the bell'))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('EC-NEW4 (boundary): while the list is still loading the open bell says so', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    const user = userEvent.setup()
    renderBell()
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading notifications…')
  })

  it('EC-NEW4 (boundary): with an unread count but an empty list, Mark all as read sends nothing', async () => {
    const { user, fetchMock } = await openWith([], 2)
    await user.click(await screen.findByRole('button', { name: 'Mark all as read' }))
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/read-all'))).toBe(false)
    expect(screen.getByRole('button', { name: 'Notifications, 2 unread' })).toBeInTheDocument()
  })

  it('EC-NEW4 (happy): Mark all as read marks up to the newest notice wherever it is listed', async () => {
    const older = notice('event_confirmed', { notificationId: 'old', occurredAt: '2026-10-27T02:00:00Z', read: false })
    const newer = notice('venue_timing_changed', { notificationId: 'new', occurredAt: '2026-10-28T02:00:00Z', read: false })
    const oldest = notice('venue_timing_conflict', { notificationId: 'oldest', occurredAt: '2026-10-26T02:00:00Z', read: false })
    const { user, fetchMock } = await openWith([older, newer, oldest], 3)
    await user.click(await screen.findByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/notifications/read-all',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ upTo: '2026-10-28T02:00:00Z' }) })))
  })

  it('EC-NEW4 (boundary): a tab being hidden does not fetch; only coming back does', async () => {
    const fetchMock = stubApi([ASSIGNED], 1)
    renderBell()
    await screen.findByLabelText('Notifications, 1 unread')
    const before = listCalls(fetchMock)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    expect(listCalls(fetchMock)).toBe(before)
  })

  it('EC-NEW4 (failure): a reply that is not readable at all (a gateway error page) still shows the load failure', async () => {
    stubApi([], 0, (url) => (url === '/api/notifications'
      ? ({ ok: false, status: 502, text: async () => '<html>Bad gateway</html>' } as Response) : undefined))
    const user = userEvent.setup()
    renderBell()
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Notifications could not be loaded.')
  })

  it('EC-NEW4 (boundary): a load that fails after the bell has gone away changes nothing', async () => {
    let fail!: (response: Response) => void
    stubApi([], 0, (url) => (url === '/api/notifications'
      ? (new Promise<Response>((resolve) => { fail = resolve }) as unknown as Response) : undefined))
    const view = renderBell()
    await waitFor(() => expect(fail).toBeTypeOf('function'))
    view.unmount()
    const errors = vi.spyOn(console, 'error')
    await act(async () => { fail(jsonResponse(500, { message: 'Down' })) })
    expect(errors).not.toHaveBeenCalled()
    errors.mockRestore()
  })

  it('EC-NEW4 (negative): with nobody signed in the bell fetches nothing', async () => {
    window.localStorage.clear()
    const fetchMock = stubApi([], 0)
    renderBell()
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
