// @stories EO03 EC-NEW3  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { ChangeRequestView, ImpactItem } from '../../types/changeRequest'
import { ChangeRequestFormPage } from './ChangeRequestFormPage'
import { ChangeRequestRedirect, ChangeRequestsPage } from './ChangeRequestsPage'
import { ChangeRequestReviewPage } from './ChangeRequestReviewPage'
import { ChangeRequestPanel } from './ChangeRequestPanel'
import { MyRequestsPage } from '../organiser/MyRequestsPage'
import { AssignedEventsPage } from '../coordinator/AssignedEventsPage'

const REQ = 'req-1'
const EVENT = 'event-1'
const CR = 'cr-1'

function journey(code: string) {
  return { code, label: code, explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false }
}

/** eo1's approved request: 10 Mar 2027, 09:00-12:00 Singapore time, 150 people. */
function request(code = 'PLANNING') {
  return {
    requestId: REQ, requestType: 'C', eventId: EVENT, eventName: 'Town Hall', purpose: 'Quarterly update',
    description: 'All hands', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
    expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null,
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false, status: 'approved',
    createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-21T01:00:00Z', organisation: 'Acme Pte Ltd',
    coordinatorId: 'coordinator-1', rejectionReason: null, journeyStatus: journey(code),
  }
}

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
const replan: ImpactItem = { key: 'safety:replan', area: 'safety', status: 'attention',
  title: 'The event goes back to Planning', detail: 'It must pass the safety check again.' }
const fits: ImpactItem = { key: 'venue:b2:ok', area: 'venue', status: 'ok', title: 'Annex still fits', detail: 'Free.' }

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/:requestId/change" element={<ChangeRequestFormPage />} />
          <Route path="/organiser/events/:eventId/change" element={<ChangeRequestFormPage />} />
          <Route path="/:console/requests/:requestId/changes" element={<ChangeRequestsPage />} />
          <Route path="/coordinator-lead/requests/:requestId/changes" element={<ChangeRequestsPage />} />
          <Route path="/:console/change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
          <Route path="/coordinator-lead/change-requests/:changeRequestId" element={<ChangeRequestReviewPage />} />
          <Route path="/change-requests/:requestId" element={<ChangeRequestRedirect />} />
          <Route path="/panel" element={<ChangeRequestPanel eventId={EVENT} journeyCode="PLANNING" />} />
          <Route path="/panel-finished" element={<ChangeRequestPanel eventId={EVENT} journeyCode="COMPLETED" />} />
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

describe('EO03 — the organiser requests a change', () => {
  function formRoutes(extra: Record<string, (body: unknown) => Response> = {}, code = 'PLANNING') {
    return {
      [`GET /api/event-requests/${REQ}`]: () => jsonResponse(200, request(code)),
      [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, []),
      ...extra,
    }
  }

  it('EO03 AC8, AC9, AC17 (happy): starts from the current approved details, every changeable field offered, times in Singapore time', async () => {
    seedSession('organiser')
    stubApi(formRoutes())
    renderAt(`/organiser/requests/${REQ}/change`)

    expect(await screen.findByLabelText('Event name')).toHaveValue('Town Hall')
    expect(screen.getByLabelText('Start date & time')).toHaveValue('2027-03-10T09:00')
    expect(screen.getByLabelText('Expected attendance')).toHaveValue(150)
    expect(screen.getAllByText('Singapore time (GMT+8)')).toHaveLength(2)
    for (const label of ['Purpose', 'Description', 'End date & time', 'Venue requirements', 'Equipment requirements',
      'Attendees need to register']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
    expect(screen.getByRole('group', { name: 'Accessibility needs' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Required facilities' })).toBeInTheDocument()
    // EO03 AC10: no way to change the organisation, coordinator, status or bookings here.
    expect(screen.queryByLabelText(/organisation|coordinator|status|venue booking/i)).not.toBeInTheDocument()
  })

  it('EO03 AC20 (boundary): with nothing changed, Review stays disabled and says why', async () => {
    seedSession('organiser')
    stubApi(formRoutes())
    renderAt(`/organiser/requests/${REQ}/change`)

    expect(await screen.findByRole('button', { name: 'Review the change' })).toBeDisabled()
    expect(screen.getByText('Change at least one detail to continue.')).toBeInTheDocument()
  })

  it('EO03 AC13, AC14, AC15, AC18 (negative): an end at the start, a past start or zero attendance is explained beside the field and blocks Review', async () => {
    seedSession('organiser')
    stubApi(formRoutes())
    renderAt(`/organiser/requests/${REQ}/change`)

    const end = await screen.findByLabelText('End date & time')
    fireEvent.change(end, { target: { value: '2027-03-10T09:00' } })
    expect(screen.getByText('The end date & time must be after the start date & time.')).toBeInTheDocument()
    fireEvent.change(end, { target: { value: '2027-03-10T12:00' } })
    fireEvent.change(screen.getByLabelText('Start date & time'), { target: { value: '2020-01-01T09:00' } })
    expect(screen.getByText('The start date & time must be in the future.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Start date & time'), { target: { value: '2027-03-10T09:00' } })
    fireEvent.change(screen.getByLabelText('Expected attendance'), { target: { value: '0' } })
    expect(screen.getByText('Expected attendance must be at least 1.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
    // A decimal cannot even be entered.
    fireEvent.change(screen.getByLabelText('Expected attendance'), { target: { value: '150.5' } })
    expect(screen.getByLabelText('Expected attendance')).toHaveValue(0)
  })

  it('EO03 AC16 (boundary): an event spanning two dates is warned that venues are booked within one day', async () => {
    seedSession('organiser')
    stubApi(formRoutes())
    renderAt(`/organiser/requests/${REQ}/change`)

    fireEvent.change(await screen.findByLabelText('End date & time'), { target: { value: '2027-03-11T01:00' } })

    expect(screen.getByText(/starts and ends on different dates/)).toBeInTheDocument()
  })

  it('EO03 AC22, AC23, AC24 (happy): the review lists previous → new, warns about arrangements and that a confirmed event goes back to Planning', async () => {
    seedSession('organiser')
    stubApi(formRoutes({}, 'CONFIRMED'))
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/change`)

    const attendance = await screen.findByLabelText('Expected attendance')
    await user.clear(attendance)
    await user.type(attendance, '200')
    await user.click(screen.getByRole('button', { name: 'Review the change' }))

    const row = screen.getByRole('row', { name: /Expected attendance/ })
    expect(row).toHaveTextContent('150')
    expect(row).toHaveTextContent('200')
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(screen.getByText(/may mean your coordinator has to rearrange/)).toBeInTheDocument()
    expect(screen.getByText(/goes back to Planning/)).toBeInTheDocument()
  })

  it('EO03 AC11, AC12 (boundary): a reason is required, counted, and over 2,000 characters is refused', async () => {
    seedSession('organiser')
    stubApi(formRoutes())
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/change`)
    fireEvent.change(await screen.findByLabelText('Event name'), { target: { value: 'Town Hall 2027' } })
    await user.click(screen.getByRole('button', { name: 'Review the change' }))

    const send = screen.getByRole('button', { name: 'Send change request' })
    expect(send).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: '   ' } })
    expect(send).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'x'.repeat(2000) } })
    expect(send).toBeEnabled()
    expect(screen.getByText(/2000 \/ 2000 characters/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'x'.repeat(2001) } })
    expect(send).toBeDisabled()
  })

  it('EO03 AC25 (happy): sending posts the full details and opens the change requests with a confirmation', async () => {
    seedSession('organiser')
    const calls = stubApi(formRoutes({
      'POST /api/change-requests': () => jsonResponse(201, view()),
      [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, []),
    }))
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/change`)
    const attendance = await screen.findByLabelText('Expected attendance')
    await user.clear(attendance)
    await user.type(attendance, '200')
    await user.click(screen.getByRole('button', { name: 'Review the change' }))
    await user.type(screen.getByLabelText('Why is this change needed?'), 'More people are coming')
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByText(/Change request sent/)).toBeInTheDocument()
    const sent = calls.find((c) => c.method === 'POST')?.body as { requestId: string; reason: string; details: Record<string, unknown> }
    expect(sent.requestId).toBe(REQ)
    expect(sent.reason).toBe('More people are coming')
    expect(sent.details.expectedAttendance).toBe(200)
    expect(sent.details.eventName).toBe('Town Hall')
  })

  it("EO03 AC13, AC41, AC42 (failure): the server's per-field refusal returns to the field with its message, keeping everything typed", async () => {
    seedSession('organiser')
    stubApi(formRoutes({
      'POST /api/change-requests': () => jsonResponse(422, { message: 'The start date & time must be in the future.',
        fieldErrors: { startDatetime: 'The start date & time must be in the future.' } }),
    }))
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/change`)
    fireEvent.change(await screen.findByLabelText('Event name'), { target: { value: 'Town Hall 2027' } })
    await user.click(screen.getByRole('button', { name: 'Review the change' }))
    await user.type(screen.getByLabelText('Why is this change needed?'), 'Rename')
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByLabelText('Start date & time')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Event name')).toHaveValue('Town Hall 2027')
    // Said once at the top and again beside the field it is about.
    expect(screen.getAllByRole('alert').filter((a) => a.textContent?.includes('must be in the future'))).toHaveLength(2)
  })

  it('EO03 AC26, AC27 (conflict): with a change already waiting, the organiser is pointed to it instead of a second form', async () => {
    seedSession('organiser')
    stubApi(formRoutes({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [view()]) }))
    renderAt(`/organiser/requests/${REQ}/change`)

    expect(await screen.findByText(/already asked to change this event/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Event name')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View your change request' })).toHaveAttribute('href', `/organiser/requests/${REQ}/changes`)
  })

  it('EO03 AC43 (failure): if the event cannot be loaded, says so and loads it on Try again', async () => {
    seedSession('organiser')
    let calls = 0
    stubApi(formRoutes({ [`GET /api/event-requests/${REQ}`]: () => (++calls === 1
      ? jsonResponse(500, { message: 'Server error' }) : jsonResponse(200, request())) }))
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/change`)

    await user.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByLabelText('Event name')).toHaveValue('Town Hall')
  })
})

describe("EO03 — another organisation's request", () => {
  it('EO03 AC1 (negative): opening the change form for a request that is not theirs says they cannot, with no Try again', async () => {
    seedSession('organiser')
    stubApi({
      [`GET /api/event-requests/${REQ}`]: () => jsonResponse(404, { message: `Event request not found: ${REQ}` }),
    })
    renderAt(`/organiser/requests/${REQ}/change`)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "You can't request a change to this event. It belongs to another organisation, or it no longer exists.")
    expect(screen.queryByText(REQ, { exact: false })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Event name')).not.toBeInTheDocument()
  })

  it("EO03 AC1 (negative): another organisation's change requests are refused with a plain message, not a retry", async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(404, { message: 'Not found' }) })
    renderAt(`/organiser/requests/${REQ}/changes`)

    expect(await screen.findByRole('alert')).toHaveTextContent("You can't view these change requests.")
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })
})

describe('EO03 — the organiser follows their change requests', () => {
  it('EO03 AC36, AC37 (happy): each change request shows its status, what it changes, the reason and the decision', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [
      view({ changeRequestId: 'cr-2', status: 'rejected', decidedByName: 'ec1', decidedAt: '2026-10-21T02:00:00Z',
        decisionReason: 'No hall holds 400' }),
    ]) })
    renderAt(`/organiser/requests/${REQ}/changes`)

    const card = (await screen.findByText('Rejected')).closest('.change-request__card') as HTMLElement
    expect(within(card).getByRole('row', { name: /Expected attendance/ })).toHaveTextContent('200')
    expect(card).toHaveTextContent('Reason: More people are coming')
    expect(card).toHaveTextContent('Rejected by ec1')
    expect(card).toHaveTextContent('No hall holds 400')
    expect(card).toHaveTextContent('(Singapore time)')
  })

  it('EO03 AC34 (happy): the organiser can withdraw a pending change after confirming, or keep it', async () => {
    seedSession('organiser')
    let withdrawn = false
    const calls = stubApi({
      [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [withdrawn
        ? view({ status: 'withdrawn', decidedAt: '2026-10-21T02:00:00Z' }) : view({ canWithdraw: true })]),
      [`POST /api/change-requests/${CR}/withdraw`]: () => { withdrawn = true; return jsonResponse(200, view({ status: 'withdrawn' })) },
    })
    const user = userEvent.setup()
    renderAt(`/organiser/requests/${REQ}/changes`)

    await user.click(await screen.findByRole('button', { name: 'Withdraw…' }))
    await user.click(screen.getByRole('button', { name: 'Keep it' }))
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Withdraw…' }))
    await user.click(screen.getByRole('button', { name: 'Withdraw' }))

    expect(await screen.findByText('Withdrawn', { selector: '.change-request__status' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Request a change' })).toBeInTheDocument()
  })

  it('EO03 AC5, AC38 (boundary): a pending change on an event that has started shows as Closed, with why, and cannot be withdrawn', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, [
      view({ canWithdraw: true, closedReason: 'The event has already started, so it can no longer be changed.' }),
    ]) })
    renderAt(`/organiser/requests/${REQ}/changes`)

    expect(await screen.findByText('Closed')).toBeInTheDocument()
    expect(screen.getByText('The event has already started, so it can no longer be changed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw…' })).not.toBeInTheDocument()
  })

  it('EO03 AC38 (happy): a change-request notification opens the reader\'s own console', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, []) })
    renderAt(`/change-requests/${REQ}`)

    expect(await screen.findByText('No changes have been requested for this event.')).toBeInTheDocument()
  })

  it('EO03 AC30 (happy): the event page shows "Change requested" while one is pending; otherwise the way to ask', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, [view()]) })
    renderAt('/panel')
    expect(await screen.findByRole('region', { name: 'Change requested' })).toHaveTextContent('waiting for your coordinator')
    cleanup()

    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, []) })
    renderAt('/panel')
    expect(await screen.findByRole('link', { name: 'Request a change' })).toHaveAttribute('href', `/organiser/events/${EVENT}/change`)
    cleanup()

    const calls = stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, []) })
    renderAt('/panel-finished')
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    expect(screen.queryByRole('link', { name: 'Request a change' })).not.toBeInTheDocument()
  })
})

describe('EC-NEW3 — the coordinator reviews and decides', () => {
  function reviewRoutes(v: ChangeRequestView, extra: Record<string, (body: unknown) => Response> = {}) {
    return { [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, v), ...extra }
  }

  it('EC-NEW3 AC5, AC6 (happy): current and proposed values side by side, and each impact marked OK or Needs attention in words', async () => {
    seedSession('coordinator')
    stubApi(reviewRoutes(view({ canDecide: true, impact: [capacity, fits] })))
    renderAt(`/coordinator/change-requests/${CR}`)

    const row = await screen.findByRole('row', { name: /Expected attendance/ })
    expect(row).toHaveTextContent('150')
    expect(row).toHaveTextContent('200')
    const venues = screen.getByRole('region', { name: 'Venues' })
    expect(within(venues).getByText('Main Hall is too small').closest('li')).toHaveTextContent('Needs attention')
    expect(within(venues).getByText('Annex still fits').closest('li')).toHaveTextContent('OK')
    expect(screen.getByText(/The organiser's reason/).parentElement).toHaveTextContent('More people are coming')
  })

  it('EC-NEW3 AC26 (boundary): Approve stays disabled until every item needing attention is ticked, then sends exactly those keys', async () => {
    seedSession('coordinator')
    const calls = stubApi(reviewRoutes(view({ canDecide: true, impact: [capacity, replan, fits] }), {
      [`POST /api/change-requests/${CR}/approve`]: () => jsonResponse(200, view({ status: 'approved', decidedByName: 'ec1',
        decidedAt: '2026-10-22T02:00:00Z', followUps: [capacity, replan] })),
    }))
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    const approve = await screen.findByRole('button', { name: 'Approve and apply the change' })
    expect(approve).toBeDisabled()
    const boxes = screen.getAllByRole('checkbox', { name: 'I have considered this' })
    expect(boxes).toHaveLength(2)
    await user.click(boxes[0])
    expect(approve).toBeDisabled()
    await user.click(boxes[1])
    await user.click(approve)

    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ acknowledged: ['venue:b1:capacity', 'safety:replan'] })
    expect(await screen.findByText(/Change approved and applied/)).toBeInTheDocument()
    // EC-NEW3 AC33: what is still to follow up.
    expect(screen.getByText('Still to follow up')).toBeInTheDocument()
  })

  it('EC-NEW3 AC7, AC29 (conflict): if the impact changed meanwhile, the latest is shown, the ticks are cleared and it says why', async () => {
    seedSession('coordinator')
    let loads = 0
    stubApi({
      [`GET /api/change-requests/${CR}`]: () => jsonResponse(200, ++loads === 1
        ? view({ canDecide: true, impact: [capacity] }) : view({ canDecide: true, impact: [capacity, replan] })),
      [`POST /api/change-requests/${CR}/approve`]: () => jsonResponse(409, {
        message: 'The impact of this change has changed since you reviewed it. Review the updated impact and approve again.' }),
    })
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    await user.click(await screen.findByRole('checkbox', { name: 'I have considered this' }))
    await user.click(screen.getByRole('button', { name: 'Approve and apply the change' }))

    expect(await screen.findByText(/has changed since you reviewed it/)).toBeInTheDocument()
    expect(await screen.findByText('The event goes back to Planning')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox', { name: 'I have considered this' }).every((b) => !(b as HTMLInputElement).checked)).toBe(true)
    expect(screen.getByRole('button', { name: 'Approve and apply the change' })).toBeDisabled()
  })

  it('EC-NEW3 AC25 (boundary): rejecting needs a reason of 1 to 2,000 characters and sends it', async () => {
    seedSession('coordinator')
    const calls = stubApi(reviewRoutes(view({ canDecide: true, impact: [fits] }), {
      [`POST /api/change-requests/${CR}/reject`]: () => jsonResponse(200, view({ status: 'rejected', decidedByName: 'ec1',
        decidedAt: '2026-10-22T02:00:00Z', decisionReason: 'No hall holds 200' })),
    }))
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    await user.click(await screen.findByRole('button', { name: 'Reject…' }))
    const reject = screen.getByRole('button', { name: 'Reject the change' })
    expect(reject).toBeDisabled()
    fireEvent.change(screen.getByLabelText("Why can't this change be made?"), { target: { value: 'y'.repeat(2001) } })
    expect(reject).toBeDisabled()
    fireEvent.change(screen.getByLabelText("Why can't this change be made?"), { target: { value: 'No hall holds 200' } })
    await user.click(reject)

    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'No hall holds 200' })
    expect(await screen.findByText(/Change rejected/)).toBeInTheDocument()
  })

  it('EC-NEW3 AC2 (cross-cutting): the Lead sees the change and its impact but cannot tick or decide', async () => {
    seedSession('coordinator-lead')
    stubApi(reviewRoutes(view({ canDecide: false, impact: [capacity] })))
    renderAt(`/coordinator-lead/change-requests/${CR}`)

    expect(await screen.findByText(/only the event's coordinator can decide it/)).toBeInTheDocument()
    expect(screen.getByText('Main Hall is too small')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Approve|Reject/ })).not.toBeInTheDocument()
  })

  it('EC-NEW3 AC28 (conflict): a change that can no longer be decided says why and offers no decision', async () => {
    seedSession('coordinator')
    stubApi(reviewRoutes(view({ canDecide: false, closedReason: 'The event has already started, so it can no longer be changed.' })))
    renderAt(`/coordinator/change-requests/${CR}`)

    expect(await screen.findByText('The event has already started, so it can no longer be changed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Approve|Reject/ })).not.toBeInTheDocument()
  })

  it('EC-NEW3 AC1 (negative): a coordinator who is not assigned is told so, with a way back', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests/${CR}`]: () => jsonResponse(403, { message: 'This is not assigned to you.' }) })
    renderAt(`/coordinator/change-requests/${CR}`)

    expect(await screen.findByRole('heading', { name: "This request isn't assigned to you" })).toBeInTheDocument()
  })

  it('EC-NEW3 AC44 (failure): if the change or its impact cannot be loaded, says so and loads it on Try again', async () => {
    seedSession('coordinator')
    let loads = 0
    stubApi({ [`GET /api/change-requests/${CR}`]: () => (++loads === 1
      ? jsonResponse(500, { message: 'Server error' }) : jsonResponse(200, view({ canDecide: true, impact: [fits] }))) })
    const user = userEvent.setup()
    renderAt(`/coordinator/change-requests/${CR}`)

    await user.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Annex still fits')).toBeInTheDocument()
  })

  it('EC-NEW3 AC3, AC33 (happy): the event page offers the pending change to review, and after approval what is still to follow up', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, [view({ canDecide: true })]) })
    renderAt('/panel')
    expect(await screen.findByRole('link', { name: 'Review and decide' })).toHaveAttribute('href', `/coordinator/change-requests/${CR}`)
    expect(screen.getByRole('region', { name: 'Change requested' })).toHaveTextContent('Expected attendance')
    cleanup()

    stubApi({ [`GET /api/change-requests?eventId=${EVENT}`]: () => jsonResponse(200, [
      view({ status: 'approved', decidedAt: '2026-10-22T02:00:00Z', followUps: [capacity] })]) })
    renderAt('/panel')
    expect(await screen.findByRole('region', { name: 'Still to follow up' })).toHaveTextContent('Main Hall is too small')
  })
})

describe('EO03 / EC-NEW3 — where a waiting change is visible', () => {
  it("EO03 AC2, AC3, AC30 (happy): the organiser's list marks a request with a change waiting, and offers a change only where one can be asked", async () => {
    seedSession('organiser')
    stubApi({
      'GET /api/event-requests': () => jsonResponse(200, [
        { ...request(), requestId: 'waiting', eventName: 'Waiting Gala' },
        { ...request(), requestId: 'free', eventName: 'Free Gala' },
        { ...request(), requestId: 'draft', eventName: 'Draft Gala', status: 'draft', eventId: null, journeyStatus: journey('DRAFT') },
      ]),
      'GET /api/change-requests': () => jsonResponse(200, [view({ requestId: 'waiting' })]),
    })
    render(<MemoryRouter><SessionProvider><MyRequestsPage /></SessionProvider></MemoryRouter>)

    const row = (name: string) => screen.getByRole('heading', { name }).closest('.organiser-home__row') as HTMLElement
    expect(await within(await screen.findByRole('heading', { name: 'Waiting Gala' }).then((h) => h.closest('.organiser-home__row') as HTMLElement))
      .findByRole('link', { name: 'Change requested' })).toHaveAttribute('href', '/organiser/requests/waiting/changes')
    expect(within(row('Free Gala')).getByRole('link', { name: 'Request a change' })).toHaveAttribute('href', '/organiser/requests/free/change')
    expect(within(row('Draft Gala')).queryByRole('link', { name: 'Request a change' })).not.toBeInTheDocument()
  })

  it("EC-NEW3 AC4 (happy): the coordinator's events list counts the changes waiting and marks each event", async () => {
    seedSession('coordinator')
    const base = { purpose: 'Update', description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
      expectedAttendance: 150, venueId: null, accessibilityNeeds: [], requiredFacilities: [], registrationNeeds: false,
      organisation: 'Acme Pte Ltd', venueRequirements: 'Theatre', equipmentRequirements: null, status: 'pending',
      coordinatorName: 'ec1', coordinatorEmail: null }
    stubApi({
      'GET /api/events/assigned': () => jsonResponse(200, [
        { ...base, eventId: EVENT, eventName: 'Town Hall' }, { ...base, eventId: 'other', eventName: 'Quiet Fair' }]),
      'GET /api/change-requests': () => jsonResponse(200, [view()]),
    })
    render(<MemoryRouter><SessionProvider><AssignedEventsPage /></SessionProvider></MemoryRouter>)

    expect(await screen.findByText('1 change request is waiting for your decision.')).toBeInTheDocument()
    const card = screen.getByRole('link', { name: 'Town Hall' })
    expect(within(card).getByText('Change requested')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Change to Town Hall' })).toHaveAttribute('href', `/coordinator/change-requests/${CR}`)
    const quiet = screen.getByRole('link', { name: 'Quiet Fair' })
    expect(within(quiet).queryByText(/Change requested/)).not.toBeInTheDocument()
  })

  it("EC-NEW3 AC3 (happy): the coordinator's events list shows each waiting change, oldest first, with organisation, reason and how long it has waited", async () => {
    seedSession('coordinator')
    const base = { purpose: 'Update', description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
      expectedAttendance: 150, venueId: null, accessibilityNeeds: [], requiredFacilities: [], registrationNeeds: false,
      organisation: 'Acme Pte Ltd', venueRequirements: 'Theatre', equipmentRequirements: null, status: 'pending',
      coordinatorName: 'ec1', coordinatorEmail: null }
    const older = view({ changeRequestId: 'cr-old', eventId: 'other', eventName: 'Quiet Fair', organisation: 'Globex Holdings',
      reason: 'Fewer guests', requestedAt: '2026-10-18T02:00:00Z' })
    stubApi({
      'GET /api/events/assigned': () => jsonResponse(200, [
        { ...base, eventId: EVENT, eventName: 'Town Hall' }, { ...base, eventId: 'other', eventName: 'Quiet Fair' }]),
      // The server sends pending changes oldest first; the page keeps that order.
      'GET /api/change-requests': () => jsonResponse(200, [older, view()]),
    })
    render(<MemoryRouter><SessionProvider><AssignedEventsPage /></SessionProvider></MemoryRouter>)

    const list = within(await screen.findByRole('region', { name: 'Change requests waiting for you' }))
    const links = list.getAllByRole('link')
    expect(links.map((l) => within(l).getByRole('heading').textContent)).toEqual(['Change to Quiet Fair', 'Change to Town Hall'])
    expect(links[0]).toHaveAttribute('href', '/coordinator/change-requests/cr-old')
    expect(list.getByText(/Globex Holdings · asked by eo1/)).toBeInTheDocument()
    expect(list.getByText('Fewer guests')).toBeInTheDocument()
    expect(list.getAllByText(/\(Singapore time\) · .+ ago/)).toHaveLength(2)
  })
})
