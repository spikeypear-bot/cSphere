import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ClarificationResponsePage } from './ClarificationResponsePage'

const ID = 'req-1'

const request = {
  requestId: ID, requestType: 'C', eventId: null, eventName: 'Town Hall', purpose: 'Quarterly update',
  description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
  expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null,
  accessibilityNeeds: ['none'], registrationNeeds: false, status: 'clarification_required',
  createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-21T01:00:00Z', organisation: 'Acme Pte Ltd',
  coordinatorId: 'ec-1', rejectionReason: null,
}

const timeline = [
  { activityId: 'a1', type: 'submitted', actorName: 'eo1', actorRole: 'eo', message: null, flaggedFields: [],
    fromStatus: 'draft', toStatus: 'pending', occurredAt: '2026-09-20T01:00:00Z' },
  { activityId: 'a2', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec',
    message: 'Is 150 the final headcount?', flaggedFields: ['expectedAttendance'],
    fromStatus: 'pending', toStatus: 'clarification_required', occurredAt: '2026-09-21T01:00:00Z' },
]

function routes(extra: Record<string, (body: unknown) => Response> = {}, status = 'clarification_required') {
  return {
    [`GET /api/event-requests/${ID}`]: () => jsonResponse(200, { ...request, status }),
    [`GET /api/event-requests/${ID}/timeline`]: () => jsonResponse(200, timeline),
    ...extra,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/organiser/requests/${ID}/respond`]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/:requestId/respond" element={<ClarificationResponsePage />} />
          <Route path="/organiser" element={<p>Organiser home</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('ClarificationResponsePage (EO26)', () => {
  beforeEach(() => seedSession('organiser'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it("shows the coordinator's question and puts the flagged field first, highlighted", async () => {
    stubApi(routes())
    renderPage()

    await screen.findByLabelText('Expected attendance')
    expect(document.querySelector('.clarify__question blockquote')).toHaveTextContent('Is 150 the final headcount?')
    const flaggedField = screen.getByText('Requested by your coordinator').closest('.clarify__field')
    expect(flaggedField).toHaveAttribute('data-flagged', 'true')
    expect(flaggedField).toContainElement(screen.getByLabelText('Expected attendance'))
    const first = document.querySelector('.clarify__form .clarify__field')
    expect(first).toBe(flaggedField)
  })

  it('cannot resubmit without a reply to the coordinator', async () => {
    stubApi(routes())
    renderPage()

    expect(await screen.findByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
  })

  it('sends the edited details and the reply together in one call, and returns home', async () => {
    const calls = stubApi(routes({
      [`POST /api/event-requests/${ID}/resubmit`]: () => jsonResponse(200, { ...request, status: 'pending' }),
    }))
    const user = userEvent.setup()
    renderPage()

    const attendance = await screen.findByLabelText('Expected attendance')
    await user.clear(attendance)
    await user.type(attendance, '120')
    await user.type(screen.getByLabelText(/Your reply to the coordinator/), '120 confirmed')
    await user.click(screen.getByRole('button', { name: 'Save and resubmit' }))

    expect(await screen.findByText('Organiser home')).toBeInTheDocument()
    const writes = calls.filter((c) => c.method !== 'GET')
    expect(writes.map((c) => c.method)).toEqual(['POST'])
    expect(writes[0].body).toMatchObject({
      response: '120 confirmed',
      details: { expectedAttendance: 120, purpose: 'Quarterly update' },
    })
  })

  it('saving alone keeps the request with the organiser', async () => {
    const calls = stubApi(routes({ [`PUT /api/event-requests/${ID}`]: () => jsonResponse(200, request) }))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Save changes' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Your request is still with you')
    expect(calls.some((c) => c.url.endsWith('/resubmit'))).toBe(false)
  })

  it('shows which required details are missing when resubmission is refused', async () => {
    stubApi(routes({
      [`POST /api/event-requests/${ID}/resubmit`]: () => jsonResponse(422, {
        message: 'Event request is missing required fields: purpose', missingFields: ['purpose'],
      }),
    }))
    const user = userEvent.setup()
    renderPage()

    await user.type(await screen.findByLabelText(/Your reply to the coordinator/), 'Updated')
    await user.click(screen.getByRole('button', { name: 'Save and resubmit' }))

    expect(await screen.findByText('Purpose is required')).toBeInTheDocument()
    expect(screen.queryByText('Organiser home')).not.toBeInTheDocument()
  })

  it("shows the coordinator's question for each field right beside that field", async () => {
    stubApi(routes({
      [`GET /api/event-requests/${ID}/timeline`]: () => jsonResponse(200, [timeline[0], {
        ...timeline[1], fieldQuestions: { expectedAttendance: 'Is 150 the final headcount?' },
      }]),
    }))
    renderPage()

    const field = (await screen.findByLabelText('Expected attendance')).closest('.clarify__field')
    expect(field).toHaveTextContent('“Is 150 the final headcount?”')
  })

  it('says so when the request is no longer waiting for clarification', async () => {
    stubApi(routes({}, 'pending'))
    renderPage()

    expect(await screen.findByRole('status')).toHaveTextContent('not waiting for clarification')
  })
})
