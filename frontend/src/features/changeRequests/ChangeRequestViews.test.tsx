// @stories EO03 EC-NEW3  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { ChangeRequestView, ImpactItem } from '../../types/changeRequest'
import { ChangeRequestPanel } from './ChangeRequestPanel'
import { ChangeRequestRedirect, ChangeRequestsPage } from './ChangeRequestsPage'
import { ChangeRequestReviewPage } from './ChangeRequestReviewPage'
import { changesPathFor, showValue, singaporeDate } from './changeRequestFields'

const REQ = 'req-1'
const EVENT = 'event-1'
const CR = 'cr-1'
const unreadable = () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response

function view(extra: Partial<ChangeRequestView> = {}): ChangeRequestView {
  return {
    changeRequestId: CR, requestId: REQ, eventId: EVENT, eventName: 'Town Hall', organisation: 'Acme Pte Ltd',
    currentStatus: 'pending', status: 'pending', closedReason: null, reason: 'More people are coming',
    requestedByName: 'eo1', requestedAt: '2026-10-20T02:00:00Z', decidedByName: null, decidedAt: null,
    decisionReason: null,
    changes: [{ field: 'expectedAttendance', label: 'Expected attendance', before: 150, after: 200 }],
    impact: null, followUps: [], canDecide: false, canWithdraw: false, ...extra,
  }
}

const capacity: ImpactItem = { key: 'venue:b1:capacity', area: 'venue', status: 'attention',
  title: 'Main Hall is too small', detail: 'It holds 180; 200 are expected.' }
const catering: ImpactItem = { key: 'catering:menu', area: 'catering', status: 'ok', title: 'Menu unchanged', detail: 'Same caterer.' }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<p>Home page</p>} />
          <Route path="/:console/requests/:requestId/changes" element={<ChangeRequestsPage />} />
          <Route path="/:console/change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
          <Route path="/change-requests/:requestId" element={<ChangeRequestRedirect />} />
          <Route path="/panel-request" element={<ChangeRequestPanel requestId={REQ} journeyCode="PLANNING" />} />
          <Route path="/panel-event" element={<ChangeRequestPanel eventId={EVENT} />} />
          <Route path="/panel-none" element={<ChangeRequestPanel />} />
          <Route path="/panel-finished-and-open" element={<>
            <ChangeRequestPanel requestId={REQ} journeyCode="COMPLETED" />
            <ChangeRequestPanel requestId={REQ} journeyCode="PLANNING" />
          </>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('EO03 / EC-NEW3 — the change request panel on event and request pages', () => {
  it('EO03 (happy): on a request page with earlier changes, the organiser can ask for another and open the earlier ones', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view({ status: 'rejected' })]) })
    renderAt('/panel-request')

    expect(await screen.findByRole('link', { name: 'Request a change' })).toHaveAttribute('href', `/organiser/requests/${REQ}/change`)
    expect(screen.getByRole('link', { name: 'Earlier change requests' })).toHaveAttribute('href', `/organiser/requests/${REQ}/changes`)
  })

  it('EO03 (happy): on an event page without a journey status the organiser is offered a change to that event', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, []) })
    renderAt('/panel-event')

    expect(await screen.findByRole('link', { name: 'Request a change' })).toHaveAttribute('href', `/organiser/events/${EVENT}/change`)
    expect(screen.queryByRole('link', { name: 'Earlier change requests' })).not.toBeInTheDocument()
  })

  it('EO03 (boundary): a finished event offers no change, while an open one with the same history does', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view({ status: 'rejected' })]) })
    renderAt('/panel-finished-and-open')

    // The finished panel's answer arrives first; by the time the open one shows, both have loaded.
    expect(await screen.findByRole('link', { name: 'Request a change' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Request a change' })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: 'Earlier change requests' })).toHaveLength(1)
  })

  it('EO03 (failure): an unexpected answer leaves the page working with "could not be loaded" and Try again', async () => {
    seedSession('organiser')
    let loads = 0
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => (++loads === 1 ? jsonResponse(200, { oops: true }) : jsonResponse(200, [])) })
    const user = userEvent.setup()
    renderAt('/panel-request')

    expect(await screen.findByText('Change requests could not be loaded.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Request a change' })).toBeInTheDocument()
  })

  it('EO03 (boundary): with no event or request to ask about, or nobody signed in, the panel shows nothing and asks nothing', async () => {
    const calls = stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, []) })
    seedSession('organiser')
    const { container } = renderAt('/panel-none')
    expect(container).toBeEmptyDOMElement()
    cleanup()
    window.localStorage.clear()

    const signedOut = renderAt('/panel-event')
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(signedOut.container).toBeEmptyDOMElement()
  })

  it('EC-NEW3 (cross-cutting): someone who cannot decide sees "View the change"; follow-ups of a change approved without a decision time are dated by the request', async () => {
    seedSession('coordinator-lead')
    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, [view()]) })
    renderAt('/panel-event')
    expect(await screen.findByRole('link', { name: 'View the change' })).toHaveAttribute('href', `/coordinator-lead/change-requests/${CR}`)
    cleanup()

    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, [view({ status: 'approved', followUps: [capacity] })]) })
    renderAt('/panel-event')
    const followUp = await screen.findByRole('region', { name: 'Still to follow up' })
    expect(within(followUp).getByRole('time')).toHaveAttribute('datetime', '2026-10-20T02:00:00Z')
  })
})

describe('EO03 / EC-NEW3 — a request\'s change requests list', () => {
  it('EO03 (failure): when withdrawing fails the organiser sees why and the list is reloaded', async () => {
    seedSession('organiser')
    let loads = 0
    stubApi({
      [`GET /api/change-requests?requestId=${REQ}`]: () => { loads++; return jsonResponse(200, [view({ canWithdraw: true })]) },
      [`POST /api/change-requests/${CR}/withdraw`]: () => jsonResponse(409, { message: 'Your coordinator has already decided this change.' }),
    })
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/changes`)

    await user.click(await screen.findByRole('button', { name: 'Withdraw…' }))
    await user.click(screen.getByRole('button', { name: 'Withdraw' }))

    expect(await screen.findByText('Your coordinator has already decided this change.')).toBeInTheDocument()
    await vi.waitFor(() => expect(loads).toBe(2))
  })

  it('EO03 (failure): an unreadable answer to withdrawing shows a general message', async () => {
    seedSession('organiser')
    stubApi({
      [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view({ canWithdraw: true })]),
      [`POST /api/change-requests/${CR}/withdraw`]: unreadable,
    })
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/changes`)

    await user.click(await screen.findByRole('button', { name: 'Withdraw…' }))
    await user.click(screen.getByRole('button', { name: 'Withdraw' }))

    expect(await screen.findByText('It could not be withdrawn. Please try again.')).toBeInTheDocument()
  })

  it('EO03 (failure): when the list cannot be loaded the organiser is told and can try again', async () => {
    seedSession('organiser')
    let loads = 0
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => (++loads === 1 ? jsonResponse(500, {}) : jsonResponse(200, [])) })
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/changes`)

    expect(await screen.findByText('The change requests could not be loaded.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No changes have been requested for this event.')).toBeInTheDocument()
  })

  it('EC-NEW3 (happy): the coordinator opens a pending change to decide it, and someone who cannot decide to view its impact', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view({ canDecide: true })]) })
    renderAt(`/coordinator/requests/${REQ}/changes`)
    expect(await screen.findByRole('link', { name: 'Review and decide' })).toHaveAttribute('href', `/coordinator/change-requests/${CR}`)
    expect(screen.queryByRole('link', { name: 'Request a change' })).not.toBeInTheDocument()
    cleanup()

    seedSession('coordinator-lead')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view()]) })
    renderAt(`/coordinator-lead/requests/${REQ}/changes`)
    expect(await screen.findByRole('link', { name: 'View impact' })).toHaveAttribute('href', `/coordinator-lead/change-requests/${CR}`)
  })

  it('EC-NEW3 (negative): a coordinator not assigned to the request is told so instead of seeing the list', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(403, { message: 'This is not assigned to you.' }) })
    renderAt(`/coordinator/requests/${REQ}/changes`)

    expect(await screen.findByRole('heading', { name: "This request isn't assigned to you" })).toBeInTheDocument()
  })

  it('EO03 (negative): a change-request link opened while signed out goes to the home page', async () => {
    stubApi({})
    renderAt(`/change-requests/${REQ}`)

    expect(await screen.findByText('Home page')).toBeInTheDocument()
  })
})

describe('EC-NEW3 — reviewing one change request', () => {
  it('EC-NEW3 (happy): a tick can be taken back, and an impact area the page does not know is still shown by its name', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, view({ canDecide: true, impact: [capacity, catering] })) })
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    expect(await screen.findByRole('region', { name: 'catering' })).toHaveTextContent('Menu unchanged')
    const box = screen.getByRole('checkbox', { name: 'I have considered this' })
    await user.click(box)
    expect(screen.getByRole('button', { name: 'Approve and apply the change' })).toBeEnabled()
    await user.click(box)
    expect(box).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Approve and apply the change' })).toBeDisabled()
  })

  it('EC-NEW3 (happy): Back from rejecting returns to the approve choice without sending anything', async () => {
    seedSession('coordinator')
    const calls = stubApi({ [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, view({ canDecide: true, impact: [] })) })
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    await user.click(await screen.findByRole('button', { name: 'Reject…' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))

    expect(screen.getByRole('button', { name: 'Approve and apply the change' })).toBeEnabled()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('EC-NEW3 (failure): a decision that fails says nothing has changed, whether the server explains or not', async () => {
    seedSession('coordinator')
    let answer = () => jsonResponse(500, { message: 'The decision service is down.' })
    stubApi({
      [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, view({ canDecide: true, impact: [] })),
      [`POST /api/change-requests/${CR}/approve`]: () => answer(),
    })
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    await user.click(await screen.findByRole('button', { name: 'Approve and apply the change' }))
    expect(await screen.findByText('The decision service is down.')).toBeInTheDocument()

    answer = unreadable
    await user.click(screen.getByRole('button', { name: 'Approve and apply the change' }))
    expect(await screen.findByText('The decision could not be saved. Nothing has changed; please try again.')).toBeInTheDocument()
  })

  it('EC-NEW3 (boundary): a withdrawn change says the organiser withdrew it', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, view({ status: 'withdrawn', decidedAt: '2026-10-21T02:00:00Z' })) })
    renderAt(`/coordinator/change-requests/${CR}`)

    expect(await screen.findByText(/Withdrawn by the organiser/)).toBeInTheDocument()
  })
})

describe('EO03 — how change details are written for people', () => {
  it('EO03 (boundary): an unknown facility is shown as sent, and missing or unreadable dates have no calendar day', () => {
    expect(showValue('requiredFacilities', ['stage', 'helipad'])).toBe('Stage, helipad')
    expect(singaporeDate(null)).toBeNull()
    expect(singaporeDate('not a date')).toBeNull()
    expect(singaporeDate('2027-03-10T17:00:00Z')).toBe('2027-03-11')
  })

  it('EO03 (boundary): without a role the change requests path has no console prefix', () => {
    expect(changesPathFor(null, REQ)).toBe(`/requests/${REQ}/changes`)
  })
})
