import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { IncomingRequestReviewPage } from './IncomingRequestReviewPage'

const REVIEW = 'GET /api/event-requests/unassigned/r1'
const DAY_MS = 24 * 60 * 60 * 1000
const daysAgo = (days: number) => new Date(Date.now() - days * DAY_MS).toISOString()

/** One timeline entry, as the server sends it. */
function entry(type: string, actorName: string, actorRole: string, overrides: Record<string, unknown> = {}) {
  return {
    activityId: `${type}-${actorName}`, type, actorName, actorRole, message: null, flaggedFields: [],
    fromStatus: null, toStatus: null, occurredAt: daysAgo(3), fieldQuestions: null, fieldValues: null,
    ...overrides,
  }
}

/** The review of a submitted request with nobody assigned. */
function review(requestOverrides: Record<string, unknown> = {}, timeline: unknown[] = [entry('submitted', 'eo1', 'eo')]) {
  return {
    request: {
      requestId: 'r1', requestType: 'C', eventId: null, eventName: 'Tech Summit',
      purpose: 'Annual customer conference', description: 'Keynotes and breakout sessions',
      // 01:00Z–09:00Z is 9:00 am–5:00 pm on 12 Nov in Singapore (UTC+8).
      startDatetime: '2026-11-12T01:00:00Z', endDatetime: '2026-11-12T09:00:00Z',
      expectedAttendance: 300, venueRequirements: 'Theatre seating for 300',
      equipmentRequirements: 'Two projectors', accessibilityNeeds: ['step_free_access', 'elevators'],
      requiredFacilities: ['stage', 'projection'], registrationNeeds: true,
      status: 'pending', createdAt: daysAgo(5), updatedAt: daysAgo(3),
      organisation: 'Globex Holdings', coordinatorId: null, rejectionReason: null, createdByName: 'eo1',
      ...requestOverrides,
    },
    missingFields: [], scheduleValid: true, timeline,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/coordinator-lead/unassigned-requests/r1']}>
      <SessionProvider>
        <Routes>
          <Route path="/coordinator-lead/unassigned-requests/:requestId" element={<IncomingRequestReviewPage />} />
          <Route path="/coordinator-lead/unassigned-requests" element={<p>Unassigned queue</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** The value shown next to a label in the submitted details. */
function detail(label: string) {
  const list = screen.getByRole('heading', { name: 'Submitted details' }).parentElement as HTMLElement
  return within(list).getByText(label, { selector: 'dt' }).nextElementSibling
}

describe('IncomingRequestReviewPage (ECL-C3 Lead review)', () => {
  beforeEach(() => seedSession('coordinator-lead', 'ecl-1'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('shows the request as the organiser submitted it', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(200, review()) })
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Tech Summit' })).toBeInTheDocument()
    // Organisation, who submitted it and when, and its current status.
    expect(screen.getByText(/Globex Holdings · submitted 3 days ago by eo1/)).toBeInTheDocument()
    expect(screen.getByText('Submitted', { selector: '.status-badge' })).toBeInTheDocument()
    expect(detail('Purpose')).toHaveTextContent('Annual customer conference')
    expect(detail('Description')).toHaveTextContent('Keynotes and breakout sessions')
    expect(detail('Start date & time')).toHaveTextContent(/12 Nov 2026/)
    expect(detail('Start date & time')).toHaveTextContent(/0?9:00/)
    expect(detail('End date & time')).toHaveTextContent(/12 Nov 2026/)
    expect(detail('End date & time')).toHaveTextContent(/0?5:00/)
    expect(detail('Expected attendance')).toHaveTextContent('300 people')
    expect(detail('Venue requirements')).toHaveTextContent('Theatre seating for 300')
    expect(detail('Equipment requirements')).toHaveTextContent('Two projectors')
    expect(detail('Accessibility requirements')).toHaveTextContent('Step-free access, Elevators')
    expect(detail('Required facilities')).toHaveTextContent('Stage, Projection')
    expect(detail('Registration')).toHaveTextContent('Registration needed')
  })

  it('says a detail was not provided rather than leaving it blank', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(200, review({ equipmentRequirements: null, requiredFacilities: [] })) })
    renderPage()

    await screen.findByRole('heading', { level: 1, name: 'Tech Summit' })
    expect(detail('Equipment requirements')).toHaveTextContent('Not provided')
    expect(detail('Required facilities')).toHaveTextContent('Not provided')
  })

  it("shows the request's history, including a clarification and the organiser's response", async () => {
    stubApi({
      [REVIEW]: () => jsonResponse(200, review({}, [
        entry('submitted', 'eo1', 'eo'),
        entry('clarification_requested', 'ecl1', 'ecl', { message: 'Who is the event for?', occurredAt: daysAgo(2) }),
        entry('clarification_responded', 'eo1', 'eo', { message: 'Our enterprise customers.', occurredAt: daysAgo(1) }),
      ])),
    })
    renderPage()

    const history = within(await screen.findByRole('complementary', { name: 'History' }))
    expect(history.getAllByRole('listitem')).toHaveLength(3)
    expect(history.getByText('Who is the event for?')).toBeInTheDocument()
    expect(history.getByText('Our enterprise customers.')).toBeInTheDocument()
  })

  it('links back to the unassigned requests list', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(200, review()) })
    renderPage()

    expect(await screen.findByRole('link', { name: 'Back to unassigned requests' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests')
  })

  it('shows "request not found" and no request data for a draft or unknown request', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(404, { message: 'Event request r1 was not found' }) })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Request not found.')
    expect(screen.queryByRole('heading', { name: 'Submitted details' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to unassigned requests' })).toBeInTheDocument()
  })

  it('shows the access-denied message and no request data when the server refuses the caller', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(403, { message: 'You do not have permission to perform this action.' }) })
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to perform this action.')
    expect(screen.queryByRole('heading', { name: 'Submitted details' })).not.toBeInTheDocument()
  })

  it('says so when the request has already been assigned to a coordinator', async () => {
    stubApi({ [REVIEW]: () => jsonResponse(200, review({ coordinatorId: 'ec-1' })) })
    renderPage()

    expect(await screen.findByText(/has been assigned to an Event Coordinator/)).toBeInTheDocument()
  })

  it.each([
    ['approved', /has been approved/],
    ['rejected', /has been rejected/],
    ['cancelled', /has been cancelled/],
  ])('says so when the request is already %s', async (status, message) => {
    stubApi({ [REVIEW]: () => jsonResponse(200, review({ status, coordinatorId: 'ec-1' })) })
    renderPage()

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  it('opening the page only reads the request', async () => {
    const calls = stubApi({ [REVIEW]: () => jsonResponse(200, review()) })
    renderPage()

    await screen.findByRole('heading', { level: 1, name: 'Tech Summit' })
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual(['GET /api/event-requests/unassigned/r1'])
  })
})
