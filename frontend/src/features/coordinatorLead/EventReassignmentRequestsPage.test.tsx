import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { EventReassignmentRequestsPage } from './EventReassignmentRequestsPage'

const REQUEST_ID = 'request-1'
const request = {
  reassignmentRequestId: REQUEST_ID, eventId: 'event-1', eventName: 'Annual Summit',
  organisation: 'Acme', eventStartDatetime: '2027-03-10T01:00:00Z',
  eventEndDatetime: '2027-03-10T04:00:00Z', requesterUserId: 'ec-1', requesterName: 'ec1',
  reason: 'I am unavailable that week.', status: 'pending', requestedAt: '2027-01-01T00:00:00Z',
  decidedAt: null, decisionReason: null, coordinatorUserId: 'ec-1', coordinatorName: 'ec1',
}
const coordinators = [
  { userId: 'ec-1', username: 'ec1' },
  { userId: 'ec-2', username: 'ec2' },
]

function renderPage() {
  return render(
    <MemoryRouter>
      <SessionProvider><EventReassignmentRequestsPage /></SessionProvider>
    </MemoryRouter>,
  )
}

describe('EventReassignmentRequestsPage (EC-C1 Lead review)', () => {
  beforeEach(() => seedSession('coordinator-lead'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('approves a request by selecting another coordinator', async () => {
    let queueReads = 0
    const calls = stubApi({
      'GET /api/event-reassignment-requests': () =>
        jsonResponse(200, queueReads++ === 0 ? [request] : []),
      'GET /api/event-reassignment-requests/coordinators': () => jsonResponse(200, coordinators),
      [`POST /api/event-reassignment-requests/${REQUEST_ID}/approve`]: () => jsonResponse(200, {}),
    })
    const user = userEvent.setup()
    renderPage()

    const card = (await screen.findByRole('heading', { name: 'Annual Summit' })).closest('li') as HTMLElement
    expect(within(card).getByText('I am unavailable that week.')).toBeInTheDocument()
    expect(within(card).getByText(/10 Mar 2027/)).toBeInTheDocument()
    await user.selectOptions(within(card).getByLabelText('Reassign to'), 'ec-2')
    await user.click(within(card).getByRole('button', { name: 'Approve and reassign' }))

    expect(await screen.findByRole('status')).toHaveTextContent('"Annual Summit" has been reassigned.')
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({ coordinatorUserId: 'ec-2' })
  })

  it('requires a decline reason and submits it with the decision', async () => {
    const calls = stubApi({
      'GET /api/event-reassignment-requests': () => jsonResponse(200, [request]),
      'GET /api/event-reassignment-requests/coordinators': () => jsonResponse(200, coordinators),
      [`POST /api/event-reassignment-requests/${REQUEST_ID}/decline`]: () => jsonResponse(200, {}),
    })
    const user = userEvent.setup()
    renderPage()
    const card = (await screen.findByRole('heading', { name: 'Annual Summit' })).closest('li') as HTMLElement
    const decline = within(card).getByRole('button', { name: 'Decline request' })
    expect(decline).toBeDisabled()

    await user.type(within(card).getByLabelText('Reason for declining'), 'Please continue planning this event.')
    await user.click(decline)

    expect(await screen.findByRole('status')).toHaveTextContent('The request for "Annual Summit" has been declined.')
    expect(calls.find((call) => call.method === 'POST')?.body)
      .toEqual({ reason: 'Please continue planning this event.' })
  })
})
