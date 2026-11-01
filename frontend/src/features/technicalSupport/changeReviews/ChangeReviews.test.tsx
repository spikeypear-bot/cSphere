// @stories TS11 TS09
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../../test/apiStubs'
import { requestPageRoutes } from '../../../test/requestPageStubs'
import { NotificationBell } from '../../notifications/NotificationBell'
import { EquipmentChangeReviewsPanel } from '../../coordinator/EquipmentChangeReviewsPanel'
import { EquipmentRequestPage } from '../../coordinator/EquipmentRequestPage'
import { EquipmentRequestDetailPage } from '../requests/EquipmentRequestDetailPage'
import { ChangeReviewPage } from './ChangeReviewPage'
import { ChangeReviewsPage } from './ChangeReviewsPage'
import type { ChangeReviewDetail } from './changeReview.types'

function request(id: string, extra: Record<string, unknown> = {}) {
  return {
    requestId: id, eventId: `event-${id}`, eventName: id === 'request-1' ? 'Town Hall' : `Event ${id}`,
    eventStart: OPEN_REVIEW.eventStart, eventEnd: OPEN_REVIEW.eventEnd,
    technicalRequirement: 'Stage left', status: 'processing', organisationName: 'Acme Pte Ltd',
    requestedBy: { userId: 'u-1', name: 'ec1' }, isShortfallRequest: false,
    items: [{ equipmentId: 'eq-proj', equipmentName: 'Projector', quantity: 2 }], eventStatus: 'PLANNING',
    ...extra,
  }
}

const OPEN_REVIEW: ChangeReviewDetail = {
  reviewId: 'review-1',
  eventId: 'event-1',
  eventName: 'Town Hall',
  eventStart: '2027-03-10T10:00:00+08:00',
  eventEnd: '2027-03-10T13:00:00+08:00',
  coordinatorName: 'ec1',
  changedAt: '2027-02-28T10:30:00+08:00',
  previous: { start: '2027-03-10T09:00:00+08:00', end: '2027-03-10T12:00:00+08:00', equipmentRequirements: null },
  updated: { start: '2027-03-10T10:00:00+08:00', end: '2027-03-10T13:00:00+08:00', equipmentRequirements: null },
  requirementsChanged: false,
  affectedAtChange: [],
  reservations: [
    { logId: 'log-1', equipmentName: 'Projector', quantity: 1, serialNumber: 'PJ-001',
      loanedFrom: '2027-03-10T01:00:00Z', loanedUntil: '2027-03-10T04:00:00Z', flagged: true },
    { logId: 'log-2', equipmentName: 'Cable', quantity: 4, serialNumber: null,
      loanedFrom: '2027-03-10T00:00:00Z', loanedUntil: '2027-03-10T06:00:00Z', flagged: false },
  ],
  needsReview: true,
  outcome: null,
  outcomeNote: null,
  reviewedByName: null,
  reviewedAt: null,
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/technical-support/change-reviews" element={<ChangeReviewsPage />} />
          <Route path="/technical-support/change-reviews/:reviewId" element={<ChangeReviewPage />} />
          <Route path="/technical-support/requests/:requestId" element={<EquipmentRequestDetailPage />} />
          <Route path="/coordinator/events/:eventId" element={<EquipmentChangeReviewsPanel eventId="event-1" />} />
          <Route path="/coordinator/events/:eventId/equipment-request" element={<EquipmentRequestPage />} />
          <Route path="/bell" element={<NotificationBell />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('TS11 Technical Support equipment notices and change reviews', () => {
  beforeEach(() => seedSession('technical-support'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('TS11 AC1, AC8 (happy): the bell titles both notices, kept to the heading for Technical Support', async () => {
    stubApi({
      'GET /api/notifications': () => jsonResponse(200, [
        { notificationId: 'n1', type: 'equipment_request_submitted', eventRequestId: null, eventId: 'event-1',
          eventName: 'Town Hall', newStatus: null, reason: null, coordinatorName: null, coordinatorEmail: null,
          isReassignment: null, occurredAt: new Date().toISOString(), read: false,
          linkPath: '/technical-support/requests/request-1',
          message: 'Town Hall, 10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM (Singapore time). Coordinator: ec1. Requested: 2 × Projector.' },
        { notificationId: 'n2', type: 'equipment_change_review', eventRequestId: null, eventId: 'event-1',
          eventName: 'Town Hall', newStatus: null, reason: null, coordinatorName: null, coordinatorEmail: null,
          isReassignment: null, occurredAt: new Date().toISOString(), read: false,
          linkPath: '/technical-support/change-reviews/review-1',
          message: 'An approved change to Town Hall affects its equipment.' },
      ]),
      'GET /api/notifications/unread-count': () => jsonResponse(200, { count: 2 }),
    })
    const user = userEvent.setup()
    renderAt('/bell')

    await user.click(await screen.findByLabelText('Notifications, 2 unread'))

    expect(await screen.findByText('New equipment request for "Town Hall"')).toBeInTheDocument()
    expect(screen.getByText('An approved change to "Town Hall" affects its equipment')).toBeInTheDocument()
    // Technical Support's list is kept to the heading; the server's full
    // sentence is only read on the request/review page it links to.
    expect(screen.queryByText(/Requested: 2 × Projector/)).not.toBeInTheDocument()
    expect(screen.queryByText('An approved change to Town Hall affects its equipment.')).not.toBeInTheDocument()
  })

  it('TS11 AC3 (happy): a request notice opens the request on the Technical Support request page', async () => {
    stubApi(requestPageRoutes(request('request-1'), {
      requests: () => [request('request-0', { eventName: 'Other Fair', technicalRequirement: '' }), request('request-1')],
      lines: () => [],
      reservations: () => [],
      availability: () => [],
    }))
    renderAt('/technical-support/requests/request-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()
    expect(screen.getByText('Stage left')).toBeInTheDocument()
  })

  it('TS11 AC3 (negative): a notice for a request no longer in the list says so', async () => {
    stubApi({ 'GET /api/equipment-requests?status=all': () => jsonResponse(200, []) })
    renderAt('/technical-support/requests/request-9')

    expect(await screen.findByText(
      'This equipment request could not be found. It may belong to an event that was cancelled or has finished.',
    )).toBeInTheDocument()
  })

  it('TS11 AC10 (happy): the review shows what changed and flags the reservation that no longer covers the event', async () => {
    stubApi({ 'GET /api/equipment-change-reviews/review-1': () => jsonResponse(200, OPEN_REVIEW) })
    renderAt('/technical-support/change-reviews/review-1')

    expect(await screen.findByRole('heading', { name: 'Town Hall' })).toBeInTheDocument()
    const table = screen.getByRole('table')
    expect(within(table).getByRole('rowheader', { name: 'Start' })).toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: 'End' })).toBeInTheDocument()
    // Unchanged requirements are not listed.
    expect(within(table).queryByRole('rowheader', { name: 'Equipment requirements' })).toBeNull()
    const flagged = screen.getByText(/1 × Projector \(PJ-001\)/).closest('li')
    expect(flagged).toHaveTextContent('Needs review: no longer covers the event')
    expect(screen.getByText(/4 × Cable/).closest('li')).not.toHaveTextContent('Needs review')
  })

  it('TS11 AC11 (negative): needing coordinator action without a note is refused before anything is sent', async () => {
    const calls = stubApi({ 'GET /api/equipment-change-reviews/review-1': () => jsonResponse(200, OPEN_REVIEW) })
    const user = userEvent.setup()
    renderAt('/technical-support/change-reviews/review-1')

    await user.click(await screen.findByLabelText('Needs coordinator action'))
    await user.click(screen.getByRole('button', { name: 'Record review' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Say what the coordinator needs to do.')
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
  })

  it('TS11 AC11, AC14 (happy): recording an outcome sends it and clears the flag', async () => {
    const calls = stubApi({
      'GET /api/equipment-change-reviews/review-1': () => jsonResponse(200, OPEN_REVIEW),
      'POST /api/equipment-change-reviews/review-1/outcome': () => jsonResponse(200, {
        ...OPEN_REVIEW,
        needsReview: false,
        outcome: 'needs_coordinator_action',
        outcomeNote: 'Ask for a projector from 12 pm',
        reviewedByName: 'ts1',
        reviewedAt: '2027-03-01T10:00:00+08:00',
        reservations: OPEN_REVIEW.reservations.map((r) => ({ ...r, flagged: false })),
      }),
    })
    const user = userEvent.setup()
    renderAt('/technical-support/change-reviews/review-1')

    await user.click(await screen.findByLabelText('Needs coordinator action'))
    await user.type(screen.getByLabelText('Note for the coordinator'), 'Ask for a projector from 12 pm')
    await user.click(screen.getByRole('button', { name: 'Record review' }))

    expect(await screen.findByText(/Needs coordinator action — reviewed by ts1/)).toBeInTheDocument()
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      outcome: 'needs_coordinator_action',
      note: 'Ask for a projector from 12 pm',
    })
    expect(screen.queryByText('Needs review: no longer covers the event')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Record review' })).toBeNull()
  })

  it('TS11 AC17 (failure): a review that cannot be loaded offers Try again, which loads it', async () => {
    let attempts = 0
    stubApi({
      'GET /api/equipment-change-reviews/review-1': () => {
        attempts += 1
        return attempts === 1 ? jsonResponse(500, { message: 'down' }) : jsonResponse(200, OPEN_REVIEW)
      },
    })
    const user = userEvent.setup()
    renderAt('/technical-support/change-reviews/review-1')

    expect(await screen.findByText(/Could not load this equipment change review\./)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Town Hall' })).toBeInTheDocument()
  })

  it('TS11 AC14 (happy): the queue marks changes still needing review', async () => {
    stubApi({
      'GET /api/equipment-change-reviews': () => jsonResponse(200, [
        { reviewId: 'review-1', eventId: 'event-1', eventName: 'Town Hall', changedAt: OPEN_REVIEW.changedAt,
          needsReview: true, affectedCount: 1, requirementsChanged: false, outcome: null, outcomeNote: null,
          reviewedByName: null, reviewedAt: null },
        { reviewId: 'review-2', eventId: 'event-2', eventName: 'Fair', changedAt: OPEN_REVIEW.changedAt,
          needsReview: false, affectedCount: 0, requirementsChanged: true, outcome: 'still_suitable', outcomeNote: null,
          reviewedByName: 'ts1', reviewedAt: OPEN_REVIEW.changedAt },
      ]),
    })
    renderAt('/technical-support/change-reviews')

    expect((await screen.findByText('Town Hall')).closest('a')).toHaveTextContent('Needs review')
    expect(screen.getByText('Fair').closest('a')).toHaveTextContent('Still suitable')
    expect(screen.getByText('Fair').closest('a')).toHaveAttribute('href', '/technical-support/change-reviews/review-2')
  })

  it('TS11 AC17 (failure): the queue offers Try again when it cannot load', async () => {
    let attempts = 0
    stubApi({
      'GET /api/equipment-change-reviews': () => {
        attempts += 1
        return attempts === 1 ? jsonResponse(500, {}) : jsonResponse(200, [])
      },
    })
    const user = userEvent.setup()
    renderAt('/technical-support/change-reviews')

    await user.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('No approved changes have affected equipment yet.')).toBeInTheDocument()
  })

  it('TS11 AC12 (happy): the coordinator sees Technical Support’s outcome and note', async () => {
    seedSession('coordinator')
    stubApi({
      'GET /api/events/event-1/equipment-change-reviews': () => jsonResponse(200, [
        { reviewId: 'review-1', eventId: 'event-1', eventName: 'Town Hall', changedAt: OPEN_REVIEW.changedAt,
          needsReview: false, affectedCount: 1, requirementsChanged: false, outcome: 'needs_coordinator_action',
          outcomeNote: 'Ask for a projector from 12 pm', reviewedByName: 'ts1', reviewedAt: OPEN_REVIEW.changedAt },
      ]),
    })
    renderAt('/coordinator/events/event-1')

    expect(await screen.findByText('Needs coordinator action')).toBeInTheDocument()
    expect(screen.getByText(/Ask for a projector from 12 pm/)).toBeInTheDocument()
  })

  it('TS11 AC2 (happy): a shortfall request is titled as one in the bell', async () => {
    stubApi({
      'GET /api/notifications': () => jsonResponse(200, [
        { notificationId: 'n1', type: 'equipment_request_submitted', eventRequestId: null, eventId: 'event-1',
          eventName: 'Town Hall', newStatus: null, reason: null, coordinatorName: null, coordinatorEmail: null,
          isReassignment: null, occurredAt: new Date().toISOString(), read: false,
          linkPath: '/technical-support/requests/request-1',
          message: 'Shortfall request for damage report report-7. Town Hall, 10 Mar 2027, 9:00 AM – 10 Mar 2027, 12:00 PM (Singapore time). Coordinator: ec1. Requested: 1 × Projector.' },
      ]),
      'GET /api/notifications/unread-count': () => jsonResponse(200, { count: 1 }),
    })
    const user = userEvent.setup()
    renderAt('/bell')

    await user.click(await screen.findByLabelText('Notifications, 1 unread'))

    expect(await screen.findByText('New shortfall request for "Town Hall"')).toBeInTheDocument()
    expect(screen.queryByText('New equipment request for "Town Hall"')).not.toBeInTheDocument()
  })

  it('TS11 AC2, AC3 (happy): the request a shortfall notice opens links to its TS09 damage report', async () => {
    const shortfall = request('request-1', { isShortfallRequest: true, shortfallReportId: 'report-7' })
    stubApi(requestPageRoutes(shortfall, {
      requests: () => [shortfall], lines: () => [], reservations: () => [], availability: () => [],
    }))
    renderAt('/technical-support/requests/request-1')

    expect(await screen.findByRole('link', { name: 'Open the damage report this request replaces' }))
      .toHaveAttribute('href', '/technical-support/faulty/reports/report-7')
  })

  it('TS11 AC2 (negative): an ordinary request has no damage report link', async () => {
    stubApi(requestPageRoutes(request('request-1'), {
      requests: () => [request('request-1')], lines: () => [], reservations: () => [], availability: () => [],
    }))
    renderAt('/technical-support/requests/request-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Town Hall' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open the damage report this request replaces' })).not.toBeInTheDocument()
  })

  it('TS11 AC12 (happy): after "needs coordinator action" the coordinator submits an additional request beside the flagged one', async () => {
    seedSession('coordinator')
    const flagged = {
      requestId: 'req-1', eventId: 'event-1', status: 'attention', technicalRequirement: 'One mic', rejectReason: null,
      attentionReason: 'An approved change may affect it.', lines: [{ equipmentId: 'eq-mic', equipmentName: 'Mic', quantity: 1 }],
    }
    const calls = stubApi({
      'GET /api/events/event-1/equipment-change-reviews': () => jsonResponse(200, [
        { reviewId: 'review-1', eventId: 'event-1', eventName: 'Town Hall', changedAt: OPEN_REVIEW.changedAt,
          needsReview: false, affectedCount: 0, requirementsChanged: true, outcome: 'needs_coordinator_action',
          outcomeNote: 'Ask for two more mics', reviewedByName: 'ts1', reviewedAt: OPEN_REVIEW.changedAt },
      ]),
      'GET /api/events/event-1': () => jsonResponse(200, {
        eventId: 'event-1', eventName: 'Town Hall', status: 'pending', equipmentRequirements: 'Three mics',
      }),
      'GET /api/equipment/catalogue': () => jsonResponse(200, [
        { equipmentId: 'eq-mic', equipmentName: 'Mic', totalQuantity: 6, serialised: false },
      ]),
      'GET /api/events/event-1/equipment-requests': () => jsonResponse(200, [flagged]),
      'POST /api/events/event-1/equipment-request': () => jsonResponse(201, { ...flagged, requestId: 'req-2', status: 'processing' }),
    })
    const user = userEvent.setup()
    renderAt('/coordinator/events/event-1')

    await user.click(await screen.findByRole('link', { name: 'Submit an additional request' }))
    expect(await screen.findByText(/Technical Support asked you to act on an approved change/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Submit equipment request' }))

    expect(await screen.findByText('Additional request submitted to Technical Support for review.')).toBeInTheDocument()
    const submitted = calls.find((call) => call.method === 'POST' && call.url === '/api/events/event-1/equipment-request')
    // The server allows it from the review outcome; no shortfall is named.
    expect(submitted?.body).toEqual({ technicalRequirement: 'Three mics', items: [] })
  })

  it('TS11 AC12 (negative): a "still suitable" outcome offers no additional request', async () => {
    seedSession('coordinator')
    stubApi({
      'GET /api/events/event-1/equipment-change-reviews': () => jsonResponse(200, [
        { reviewId: 'review-1', eventId: 'event-1', eventName: 'Town Hall', changedAt: OPEN_REVIEW.changedAt,
          needsReview: false, affectedCount: 0, requirementsChanged: true, outcome: 'still_suitable',
          outcomeNote: null, reviewedByName: 'ts1', reviewedAt: OPEN_REVIEW.changedAt },
      ]),
    })
    renderAt('/coordinator/events/event-1')

    expect(await screen.findByText('Still suitable')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Submit an additional request' })).not.toBeInTheDocument()
  })
})
