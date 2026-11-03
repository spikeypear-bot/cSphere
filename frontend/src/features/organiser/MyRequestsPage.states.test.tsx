// @stories EO15 EO07  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { MyRequestsPage } from './MyRequestsPage'

const unreadable = () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response
const journey = (code: string, label: string) =>
  ({ code, label, explanation: '', nextActor: null, stage: 'REQUEST', isFinal: false, datePassed: false })

function row(requestId: string, over: Record<string, unknown> = {}) {
  return {
    requestId, requestType: 'C', eventId: null, eventName: `Event ${requestId}`, status: 'pending',
    accessibilityNeeds: [], organisation: 'Acme Pte Ltd', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
    journeyStatus: journey('UNDER_REVIEW', 'Under Review'), ...over,
  }
}

function renderList(state?: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/organiser/requests', state }]}>
      <SessionProvider>
        <Routes><Route path="/organiser/requests" element={<MyRequestsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EO15 — the organiser\'s request list: failures, filters and unnamed requests', () => {
  beforeEach(() => seedSession('organiser'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EO15 (failure): when the list cannot be loaded the organiser is told why, and the change badges failing does not matter', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(503, { message: 'Requests are unavailable right now.' }),
      'GET /api/change-requests': () => jsonResponse(500, {}),
    })
    const first = renderList()
    expect(await screen.findByRole('alert')).toHaveTextContent('Requests are unavailable right now.')
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
    first.unmount()

    stubApi({ 'GET /api/event-requests': unreadable, 'GET /api/change-requests': () => jsonResponse(200, []) })
    renderList()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your event requests.')
  })

  it('EO15 (happy): after resubmitting a clarification the organiser sees it was sent back for review', async () => {
    stubApi({ 'GET /api/event-requests': () => jsonResponse(200, []), 'GET /api/change-requests': () => jsonResponse(200, []) })
    renderList({ justResubmitted: true })

    expect(await screen.findByText('Your updated request has been sent back for review.')).toBeInTheDocument()
  })

  it('EO15 (happy): choosing a status shows only those requests, and All shows them all again', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        row('a'), row('b', { status: 'rejected', journeyStatus: journey('REJECTED', 'Rejected') })]),
      'GET /api/change-requests': () => jsonResponse(200, []),
    })
    const user = userEvent.setup()
    renderList()
    const filter = await screen.findByRole('group', { name: 'Filter your requests by status' })

    await user.click(within(filter).getByRole('button', { name: /Rejected/ }))
    expect(screen.queryByRole('heading', { name: 'Event a' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Event b' })).toBeInTheDocument()
    await user.click(within(filter).getByRole('button', { name: /All/ }))
    expect(screen.getByRole('heading', { name: 'Event a' })).toBeInTheDocument()
  })

  it('EO15 (boundary): a request with no name is listed as "Untitled draft", and a question without text is not shown', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        row('q', { eventName: '', status: 'clarification_required', journeyStatus: journey('CLARIFICATION_REQUIRED', 'Clarification Required') }),
      ]),
      'GET /api/event-requests/q/timeline': () => jsonResponse(200, [{ activityId: 'x', type: 'clarification_requested',
        actorName: 'ec1', actorRole: 'ec', message: null, flaggedFields: [], occurredAt: '2026-10-01T00:00:00Z' }]),
      'GET /api/change-requests': () => jsonResponse(200, []),
    })
    renderList()

    expect(await screen.findByRole('heading', { name: 'Untitled draft' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel request Untitled request' })).toBeInTheDocument()
    expect(screen.queryByText(/asked:/)).not.toBeInTheDocument()
  })

  it('EO15 (happy): a request waiting for clarification shows who asked what, right on the list', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        row('q', { status: 'clarification_required', journeyStatus: journey('CLARIFICATION_REQUIRED', 'Clarification Required') }),
      ]),
      'GET /api/event-requests/q/timeline': () => jsonResponse(200, [
        { activityId: 'x', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec', message: 'Old question?',
          flaggedFields: [], occurredAt: '2026-10-01T00:00:00Z' },
        { activityId: 'y', type: 'clarification_requested', actorName: 'ecl1', actorRole: 'ecl', message: 'How many rooms?',
          flaggedFields: [], occurredAt: '2026-10-02T00:00:00Z' },
      ]),
      'GET /api/change-requests': () => jsonResponse(200, []),
    })
    renderList()

    const question = (await screen.findByText('ecl1 asked:')).closest('p')
    expect(question).toHaveTextContent('How many rooms?')
    expect(screen.queryByText(/Old question/)).not.toBeInTheDocument()
  })

  it('EO15 (failure): when the latest question cannot be read the row still shows without it', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        row('q', { status: 'clarification_required', journeyStatus: journey('CLARIFICATION_REQUIRED', 'Clarification Required') }),
      ]),
      'GET /api/event-requests/q/timeline': () => jsonResponse(200, { unexpected: true }),
      'GET /api/change-requests': () => jsonResponse(200, []),
    })
    const first = renderList()
    expect(await screen.findByRole('link', { name: 'Respond' })).toBeInTheDocument()
    first.unmount()

    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        row('q', { status: 'clarification_required', journeyStatus: journey('CLARIFICATION_REQUIRED', 'Clarification Required') }),
      ]),
      'GET /api/event-requests/q/timeline': () => jsonResponse(500, {}),
      'GET /api/change-requests': () => jsonResponse(200, []),
    })
    renderList()
    expect(await screen.findByRole('link', { name: 'Respond' })).toHaveAttribute('href', '/organiser/requests/q/respond')
    expect(screen.queryByText(/asked:/)).not.toBeInTheDocument()
  })

  it('EO07 (happy): cancelling an unnamed request confirms it as "Your request"', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [row('u', { eventName: null })]),
      'GET /api/change-requests': () => jsonResponse(200, []),
      'POST /api/event-requests/u/cancel': () => jsonResponse(200, row('u', { eventName: null, status: 'cancelled',
        journeyStatus: journey('CANCELLED', 'Cancelled') })),
    })
    const user = userEvent.setup()
    renderList()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Untitled request' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Budget cut')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByText('"Your request" has been cancelled.')).toBeInTheDocument()
  })

  it('EO07 (failure): an unreadable answer to cancelling says it could not be cancelled', async () => {
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [row('u')]),
      'GET /api/change-requests': () => jsonResponse(200, []),
      'POST /api/event-requests/u/cancel': unreadable,
    })
    const user = userEvent.setup()
    renderList()

    await user.click(await screen.findByRole('button', { name: 'Cancel request Event u' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Budget cut')
    await user.click(screen.getByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The request could not be cancelled. Please try again.')
  })
})
