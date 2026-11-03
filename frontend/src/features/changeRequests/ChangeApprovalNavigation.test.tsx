// @stories EC-NEW3
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { ChangeRequestView } from '../../types/changeRequest'
import { ChangeRequestReviewPage } from './ChangeRequestReviewPage'

const pending: ChangeRequestView = {
  changeRequestId: 'change-1', requestId: 'request-1', eventId: 'original-event', eventName: 'Town Hall',
  organisation: 'Acme', currentStatus: 'pending', status: 'pending', closedReason: null,
  reason: 'More attendees', requestedByName: 'eo1', requestedAt: '2026-11-01T00:00:00Z',
  decidedByName: null, decidedAt: null, decisionReason: null, changes: [], impact: [], followUps: [],
  canDecide: true, canWithdraw: false,
}

function Destination() {
  const { eventId } = useParams()
  const location = useLocation()
  return <><h1>Event {eventId}</h1><output aria-label="Approval notice">{String(location.state?.changeApproved)}</output></>
}

function renderReview() {
  seedSession('coordinator')
  render(<MemoryRouter initialEntries={['/coordinator/change-requests/change-1']}><SessionProvider><Routes>
    <Route path="/coordinator/change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
    <Route path="/coordinator/events/:eventId" element={<Destination />} />
  </Routes></SessionProvider></MemoryRouter>)
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })

describe('EC-NEW3 approval navigation', () => {
  it('EC-NEW3 (happy): waits for success and opens the actual returned event with the success notice', async () => {
    let finish!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => init?.method === 'POST'
      ? new Promise<Response>(resolve => { finish = resolve }) : Promise.resolve(jsonResponse(200, pending))))
    renderReview()
    await userEvent.click(await screen.findByRole('button', { name: 'Approve and apply the change' }))
    expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled()
    expect(screen.queryByRole('heading', { name: 'Event saved-event' })).not.toBeInTheDocument()
    await act(async () => finish(jsonResponse(200, { ...pending, eventId: 'saved-event', status: 'approved', canDecide: false })))
    expect(await screen.findByRole('heading', { name: 'Event saved-event' })).toBeInTheDocument()
    expect(screen.getByLabelText('Approval notice')).toHaveTextContent('true')
  })

  it('EC-NEW3 (failure): a refused approval keeps the review and displays the server feedback', async () => {
    stubApi({
      'GET /api/change-requests/change-1': () => jsonResponse(200, pending),
      'POST /api/change-requests/change-1/approve': () => jsonResponse(500, { message: 'Approval could not be saved' }),
    })
    renderReview()
    await userEvent.click(await screen.findByRole('button', { name: 'Approve and apply the change' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Approval could not be saved')
    expect(screen.getByRole('heading', { name: 'Change to Town Hall' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Event / })).not.toBeInTheDocument()
  })

  it('EC-NEW3 (negative): rejection keeps its existing confirmation and never opens the event', async () => {
    stubApi({
      'GET /api/change-requests/change-1': () => jsonResponse(200, pending),
      'POST /api/change-requests/change-1/reject': () => jsonResponse(200, { ...pending, status: 'rejected', canDecide: false }),
    })
    renderReview()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Reject…' }))
    await user.type(screen.getByLabelText("Why can't this change be made?"), 'Venue cannot accommodate this')
    await user.click(screen.getByRole('button', { name: 'Reject the change' }))
    expect(await screen.findByText('Change rejected. The organiser has been told why.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Event / })).not.toBeInTheDocument()
  })

  it('EC-NEW3 (boundary): approval before an event exists retains feedback without inventing an event route', async () => {
    stubApi({
      'GET /api/change-requests/change-1': () => jsonResponse(200, { ...pending, eventId: null }),
      'POST /api/change-requests/change-1/approve': () => jsonResponse(200, { ...pending, eventId: null, status: 'approved', canDecide: false }),
    })
    renderReview()
    await userEvent.click(await screen.findByRole('button', { name: 'Approve and apply the change' }))
    expect(await screen.findByText('Change approved and applied. The organiser has been told.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Event / })).not.toBeInTheDocument()
  })
})
