// @stories EO07
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { EventRequestDto, EventRequestStatus } from '../../types/eventRequest'
import type { StatusView } from '../../types/status'
import { MyRequestsPage } from './MyRequestsPage'

const LABELS: Record<EventRequestStatus, [StatusView['code'], string]> = {
  draft: ['DRAFT', 'Draft'],
  pending: ['UNDER_REVIEW', 'Under Review'],
  clarification_required: ['CLARIFICATION_REQUIRED', 'Clarification Required'],
  approved: ['PLANNING', 'Planning'],
  rejected: ['REJECTED', 'Rejected'],
  cancelled: ['CANCELLED', 'Cancelled'],
}

function request(status: EventRequestStatus, over: Partial<EventRequestDto> = {}): EventRequestDto {
  const [code, label] = LABELS[status]
  return {
    requestId: 'req-1', requestType: 'C', eventId: status === 'approved' ? 'ev-1' : null, eventName: 'Spring Gala',
    purpose: 'Launch', description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
    expectedAttendance: 80, venueRequirements: 'Theatre', equipmentRequirements: null, accessibilityNeeds: ['none'],
    registrationNeeds: false, status, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
    organisation: 'Acme Pte Ltd', coordinatorId: null, rejectionReason: null,
    journeyStatus: { code, label, explanation: `${label}.`, nextActor: null, stage: 'REQUEST',
      isFinal: status === 'rejected' || status === 'cancelled', datePassed: false },
    ...over,
  }
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/organiser/requests']}>
      <SessionProvider>
        <Routes><Route path="/organiser/requests" element={<MyRequestsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** The organiser's list with one request, plus the timeline a clarification row loads. */
function listWith(row: EventRequestDto, extra: Record<string, (body: unknown) => Response> = {}) {
  return stubApi({
    'GET /api/event-requests': () => jsonResponse(200, [row]),
    [`GET /api/event-requests/${row.requestId}/timeline`]: () => jsonResponse(200, []),
    ...extra,
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('EO07 organiser cancels a request before approval', () => {
  it.each(['pending', 'clarification_required'] as const)(
    'EO07 AC2 (happy): a %s request offers "Cancel request"', async (status) => {
      seedSession('organiser')
      listWith(request(status))
      renderHome()

      expect(await screen.findByRole('button', { name: 'Cancel request Spring Gala' })).toBeInTheDocument()
    })

  it('EO07 AC2 (happy): a Submitted request with no coordinator yet offers "Cancel request"', async () => {
    seedSession('organiser')
    listWith(request('pending', { journeyStatus: { code: 'SUBMITTED', label: 'Submitted', explanation: '.',
      nextActor: null, stage: 'REQUEST', isFinal: false, datePassed: false } }))
    renderHome()

    expect(await screen.findByRole('button', { name: 'Cancel request Spring Gala' })).toBeInTheDocument()
  })

  it.each(['draft', 'rejected', 'cancelled', 'approved'] as const)(
    'EO07 AC5 (negative): a %s request offers no "Cancel request"', async (status) => {
      seedSession('organiser')
      listWith(request(status))
      renderHome()

      expect(await screen.findByText('Spring Gala')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Cancel request/ })).not.toBeInTheDocument()
    })

  it('EO07 AC4 (negative): an empty reason is refused with a message and nothing is sent', async () => {
    seedSession('organiser')
    const calls = listWith(request('pending'))
    const user = userEvent.setup()
    renderHome()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Spring Gala' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), '   ')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Please give a reason for cancelling this request.')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('EO07 AC4 (boundary): a reason over 2000 characters is refused before sending', async () => {
    seedSession('organiser')
    const calls = listWith(request('pending'))
    const user = userEvent.setup()
    renderHome()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Spring Gala' }))
    await user.click(screen.getByLabelText('Reason for cancelling'))
    await user.paste('r'.repeat(2001))
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(screen.getByRole('alert')).toHaveTextContent('at most 2000 characters')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('EO07 AC2 (negative): "Keep request" closes the form without sending anything', async () => {
    seedSession('organiser')
    const calls = listWith(request('pending'))
    const user = userEvent.setup()
    renderHome()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Spring Gala' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Changed plans')
    await user.click(screen.getByRole('button', { name: 'Keep request' }))

    expect(screen.queryByLabelText('Reason for cancelling')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel request Spring Gala' })).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('EO07 AC2 (happy): confirming sends the trimmed reason and the row shows Cancelled, with no further cancel action', async () => {
    seedSession('organiser')
    const calls = listWith(request('pending'), {
      'POST /api/event-requests/req-1/cancel': () => jsonResponse(200, request('cancelled')),
    })
    const user = userEvent.setup()
    renderHome()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Spring Gala' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), '  The client called it off  ')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByText('"Spring Gala" has been cancelled.')).toBeInTheDocument()
    const post = calls.find((c) => c.method === 'POST')
    expect(post?.url).toBe('/api/event-requests/req-1/cancel')
    expect(post?.body).toEqual({ reason: 'The client called it off' })
    const row = screen.getByRole('heading', { name: 'Spring Gala' }).closest('li') as HTMLElement
    expect(within(row).getAllByText('Cancelled').length).toBeGreaterThan(0)
    expect(within(row).queryByRole('button', { name: /Cancel request/ })).not.toBeInTheDocument()
  })

  it('EO07 AC14 (failure): a server error is shown, the request keeps its status and the user can try again', async () => {
    seedSession('organiser')
    listWith(request('pending'), {
      'POST /api/event-requests/req-1/cancel': () => jsonResponse(409,
        { message: 'Only a request that is Submitted, Under Review or Clarification Required can be cancelled (this one is Planning).' }),
    })
    const user = userEvent.setup()
    renderHome()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Spring Gala' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Called off')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('(this one is Planning)')
    const row = screen.getByRole('heading', { name: 'Spring Gala' }).closest('li') as HTMLElement
    expect(within(row).getAllByText('Under Review').length).toBeGreaterThan(0)
    expect(within(row).queryByText('Cancelled')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm cancellation' })).toBeEnabled()
  })
})
