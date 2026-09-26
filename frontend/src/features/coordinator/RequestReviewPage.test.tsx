import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { RequestReviewPage, type EventRequestReview } from './RequestReviewPage'
import { composeClarification } from './clarificationDraft'

const ID = 'req-1'
const REVIEW_URL = `GET /api/event-requests/${ID}/review`

function review(overrides: Partial<EventRequestReview['request']> = {}, extra: Partial<EventRequestReview> = {}): EventRequestReview {
  return {
    request: {
      requestId: ID, requestType: 'C', eventId: null, eventName: 'Town Hall', purpose: 'Quarterly update',
      description: 'All hands', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
      expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null,
      accessibilityNeeds: ['none'], registrationNeeds: false, status: 'pending',
      createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-20T01:00:00Z', organisation: 'Acme Pte Ltd',
      coordinatorId: 'coordinator-1', rejectionReason: null, ...overrides,
    },
    missingFields: [],
    scheduleValid: true,
    timeline: [
      { activityId: 'a1', type: 'submitted', actorName: 'eo1', actorRole: 'eo', message: null, flaggedFields: [],
        fromStatus: 'draft', toStatus: 'pending', occurredAt: '2026-09-20T01:00:00Z' },
    ],
    ...extra,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/coordinator/requests/${ID}`]}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator/requests/:requestId" element={<RequestReviewPage />} />
          <Route path="/coordinator/events/:eventId" element={<p>Event page</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('RequestReviewPage (EC02 review, EC01 clarification)', () => {
  beforeEach(() => seedSession('coordinator', 'coordinator-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows every submitted detail and the request history', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review()) })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Town Hall' })).toBeInTheDocument()
    for (const text of ['Quarterly update', 'All hands', '150 people', 'Theatre seating',
      'No accessibility requirements needed', 'No registration']) {
      expect(screen.getByText(text)).toBeInTheDocument()
    }
    expect(screen.getByRole('list', { name: 'Request timeline' })).toHaveTextContent('submitted the request')
  })

  it('explains what blocks approval and disables Approve while details are missing', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review({ purpose: null }, { missingFields: ['purpose'] })) })
    renderPage()

    expect(await screen.findByText('Purpose is missing')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled()
  })

  it('asking about missing details pre-flags them, each with its own question box', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review({ purpose: null }, { missingFields: ['purpose'] })) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Ask the organiser about these' }))

    expect(screen.getByRole('list', { name: 'Flagged fields' })).toHaveTextContent('Purpose')
    expect(screen.getByLabelText('What do you need to know more from the organiser about the Purpose?')).toHaveValue('')
  })

  it('shows one question per flagged field and updates as fields are flagged and unflagged', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review()) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Flag Start date & time' }))
    await user.click(screen.getByRole('button', { name: 'Flag Venue requirements' }))
    await user.click(screen.getByRole('button', { name: /Request clarification/ }))

    const start = screen.getByLabelText('What do you need to know more from the organiser about the Start date & time?')
    const venue = screen.getByLabelText('What do you need to know more from the organiser about the Venue requirements?')
    expect(start.compareDocumentPosition(venue) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('Start date & time', { selector: 'em' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Remove Start date & time' }))
    expect(screen.queryByLabelText(/about the Start date & time\?/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Flag Expected attendance' }))
    expect(screen.getByLabelText('What do you need to know more from the organiser about the Expected attendance?'))
      .toBeInTheDocument()
  })

  it('cannot send until every flagged field has a question', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review()) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Flag Start date & time' }))
    await user.click(screen.getByRole('button', { name: 'Flag Venue requirements' }))
    await user.click(screen.getByRole('button', { name: /Request clarification/ }))
    await user.type(screen.getByLabelText(/about the Start date & time\?/), 'Can it start at 10am?')

    expect(screen.getByRole('button', { name: 'Send clarification request' })).toBeDisabled()
  })

  it('sends a clarification with the flagged fields and confirms it was sent', async () => {
    let sent = false
    const calls = stubApi({
      [REVIEW_URL]: () => jsonResponse(200, sent ? review({ status: 'clarification_required' }) : review()),
      [`POST /api/event-requests/${ID}/clarifications`]: () => {
        sent = true
        return jsonResponse(200, review({ status: 'clarification_required' }).request)
      },
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Flag Expected attendance' }))
    await user.click(screen.getByRole('button', { name: 'Flag Venue requirements' }))
    await user.click(screen.getByRole('button', { name: /Request clarification/ }))
    await user.type(screen.getByLabelText(/about the Expected attendance\?/), 'Is 150 final?')
    await user.type(screen.getByLabelText(/about the Venue requirements\?/), 'Theatre or banquet?')
    await user.click(screen.getByRole('button', { name: 'Send clarification request' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Clarification sent')
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      message: 'Expected attendance: Is 150 final?\n\nVenue requirements: Theatre or banquet?',
      flaggedFields: ['expectedAttendance', 'venueRequirements'],
      fieldQuestions: { expectedAttendance: 'Is 150 final?', venueRequirements: 'Theatre or banquet?' },
    })
  })

  it('will not send a blank clarification', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review()) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Request clarification/ }))
    await user.type(screen.getByLabelText('What do you need from the organiser?'), '   ')

    expect(screen.getByRole('button', { name: 'Send clarification request' })).toBeDisabled()
  })

  it('will not send a clarification over 2000 characters', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(200, review()) })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Request clarification/ }))
    const box = screen.getByLabelText('What do you need from the organiser?')
    await user.click(box)
    await user.paste('x'.repeat(2001))

    expect(screen.getByText('2001/2000')).toHaveClass('field-error')
    expect(screen.getByRole('button', { name: 'Send clarification request' })).toBeDisabled()
  })

  it('approving opens the new event', async () => {
    stubApi({
      [REVIEW_URL]: () => jsonResponse(200, review()),
      [`POST /api/event-requests/${ID}/approve`]: () => jsonResponse(200, { ...review().request, status: 'approved', eventId: 'ev-9' }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    await user.click(screen.getByRole('button', { name: 'Approve and start planning' }))

    expect(await screen.findByText('Event page')).toBeInTheDocument()
  })

  it('while waiting for the organiser, only rejection is offered and the question is shown', async () => {
    stubApi({
      [REVIEW_URL]: () => jsonResponse(200, review({ status: 'clarification_required' }, {
        timeline: [{ activityId: 'a2', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec',
          message: 'Is 150 final?', flaggedFields: ['expectedAttendance'], fromStatus: 'pending',
          toStatus: 'clarification_required', occurredAt: '2026-09-21T01:00:00Z' }],
      })),
    })
    renderPage()

    expect(await screen.findByText(/Waiting for the organiser/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Request clarification/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('after a resubmission, shows the reply and what changed since the question, and marks changed rows', async () => {
    stubApi({
      [REVIEW_URL]: () => jsonResponse(200, review({ expectedAttendance: 120 }, {
        timeline: [
          { activityId: 'a2', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec', message: 'Is 150 final?',
            flaggedFields: ['expectedAttendance'], fromStatus: 'pending', toStatus: 'clarification_required',
            occurredAt: '2026-09-21T01:00:00Z', fieldValues: { expectedAttendance: 150, purpose: 'Quarterly update' } },
          { activityId: 'a3', type: 'clarification_responded', actorName: 'eo1', actorRole: 'eo', message: '120 confirmed',
            flaggedFields: [], fromStatus: 'clarification_required', toStatus: 'pending',
            occurredAt: '2026-09-22T01:00:00Z', fieldValues: { expectedAttendance: 120, purpose: 'Quarterly update' } },
        ],
      })),
    })
    renderPage()

    const changes = await screen.findByRole('list', { name: 'What changed since you asked' })
    expect(changes).toHaveTextContent('Expected attendance: 150 → 120')
    expect(changes).not.toHaveTextContent('Purpose')
    expect(screen.getByText('Updated').closest('.request-review__row')).toHaveTextContent('120 people')
  })

  it('tells a coordinator who is not assigned that they cannot review it', async () => {
    stubApi({ [REVIEW_URL]: () => jsonResponse(403, { message: 'Forbidden' }) })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('not assigned to you')
  })

  it('shows a server error from a failed clarification and keeps what was typed', async () => {
    stubApi({
      [REVIEW_URL]: () => jsonResponse(200, review()),
      [`POST /api/event-requests/${ID}/clarifications`]: () => jsonResponse(409, {
        message: 'Clarification has already been requested.',
      }),
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /Request clarification/ }))
    await user.type(screen.getByLabelText('What do you need from the organiser?'), 'Please confirm')
    await user.click(screen.getByRole('button', { name: 'Send clarification request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Clarification has already been requested.')
    expect(screen.getByLabelText('What do you need from the organiser?')).toHaveValue('Please confirm')
  })
})

describe('composeClarification', () => {
  it('labels each question with its field, in flag order, skipping blank ones', () => {
    expect(composeClarification([], {})).toBe('')
    expect(composeClarification(['endDatetime', 'purpose', 'description'],
      { purpose: '  Why? ', endDatetime: '5pm?', description: '   ' }))
      .toBe('End date & time: 5pm?\n\nPurpose: Why?')
  })
})
