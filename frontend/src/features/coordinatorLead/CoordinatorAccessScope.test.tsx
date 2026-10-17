import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { StatusView } from '../../types/status'
import { EventDetailsPage } from '../events/EventDetailsPage'
import { CoordinatorAssignmentsPage } from './CoordinatorAssignmentsPage'
import { IncomingRequestReviewPage } from './IncomingRequestReviewPage'

// ECL-C7: each Event Coordinator works only on what the Lead assigned them;
// the Lead sees every assignment, can view any event and reassign it, but
// does not take coordinator actions.

const EVENT = 'ev-7'
const status = (code: StatusView['code'], label: string, isFinal = false): StatusView => ({
  code, label, explanation: `${label} explained.`, nextActor: null, stage: 'EVENT', isFinal, datePassed: false,
})
const eventDto = {
  eventId: EVENT, eventName: 'Harbour Gala', purpose: 'Annual dinner', description: null,
  startDatetime: '2027-05-01T11:00:00Z', endDatetime: '2027-05-01T14:00:00Z', expectedAttendance: 200,
  venueId: null, accessibilityNeeds: [], registrationNeeds: null, organisation: 'Acme Pte Ltd',
  venueRequirements: 'Banquet', equipmentRequirements: null, status: 'pending',
  coordinatorName: 'ec1', coordinatorEmail: 'ec1@connectsphere.test', journeyStatus: status('PLANNING', 'Planning'),
}
const approvedRequest = (journey: StatusView) => ({
  requestId: 'r7', requestType: 'C', eventId: EVENT, eventName: 'Harbour Gala', purpose: 'Annual dinner',
  description: null, startDatetime: eventDto.startDatetime, endDatetime: eventDto.endDatetime, expectedAttendance: 200,
  venueRequirements: 'Banquet', accessibilityNeeds: [], requiredFacilities: [], equipmentRequirements: null,
  registrationNeeds: null, organisation: 'Acme Pte Ltd', status: 'approved', coordinatorId: 'ec1-id',
  createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z', journeyStatus: journey,
})

function renderAt(path: string, route: string, element: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes><Route path={route} element={element} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('ECL-C7 coordinator access is limited to assigned work', () => {
  it('ECL-C7 AC2, AC18 (negative): a coordinator opening an event that is not theirs is told so, with a way back, and sees no event data', async () => {
    seedSession('coordinator')
    stubApi({ [`GET /api/events/${EVENT}`]: () => jsonResponse(403, { message: 'This is not assigned to you.' }) })
    renderAt(`/coordinator/events/${EVENT}`, '/coordinator/events/:eventId', <EventDetailsPage />)

    expect(await screen.findByRole('heading', { name: "This event isn't assigned to you" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to My events' })).toHaveAttribute('href', '/coordinator/my-events')
    expect(screen.queryByText('Harbour Gala')).not.toBeInTheDocument()
  })

  it('ECL-C7 AC20 (happy): the Lead sees each coordinator\'s active events, and can view or reassign each one', async () => {
    seedSession('coordinator-lead')
    stubApi({
      'GET /api/event-requests/assigned': () => jsonResponse(200, [
        { coordinatorId: 'ec1-id', coordinatorName: 'ec1', requests: [] },
        { coordinatorId: 'ec2-id', coordinatorName: 'ec2', requests: [] },
      ]),
      'GET /api/events/by-coordinator': () => jsonResponse(200, [
        { coordinatorId: 'ec1-id', coordinatorName: 'ec1', events: [{ requestId: 'r7', event: eventDto }] },
        { coordinatorId: 'ec2-id', coordinatorName: 'ec2', events: [] },
      ]),
    })
    renderAt('/coordinator-lead/assignments', '/coordinator-lead/assignments', <CoordinatorAssignmentsPage />)

    const list = await screen.findByRole('list', { name: 'Events held by ec1' })
    expect(within(list).getByText('Harbour Gala')).toBeInTheDocument()
    expect(within(list).getByText('Planning')).toBeInTheDocument()
    expect(within(list).getByRole('link', { name: 'View event: Harbour Gala' }))
      .toHaveAttribute('href', `/coordinator-lead/events/${EVENT}`)
    expect(within(list).getByRole('link', { name: 'Reassign: Harbour Gala' }))
      .toHaveAttribute('href', '/coordinator-lead/unassigned-requests/r7')
    expect(screen.getByText('Holding 1 active event')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Events held by ec2' })).not.toBeInTheDocument()
  })

  it('ECL-C7 AC14, AC15 (happy): an approved request whose event is active can be reassigned, and the Lead is told what that does', async () => {
    seedSession('coordinator-lead')
    stubApi({
      'GET /api/event-requests/unassigned/r7': () => jsonResponse(200, {
        request: approvedRequest(status('PLANNING', 'Planning')), missingFields: [], scheduleValid: true, timeline: [],
      }),
      'GET /api/event-requests/unassigned/coordinators': () => jsonResponse(200, [
        { userId: 'ec1-id', username: 'ec1' }, { userId: 'ec2-id', username: 'ec2' },
      ]),
    })
    renderAt('/coordinator-lead/unassigned-requests/r7', '/coordinator-lead/unassigned-requests/:requestId',
      <IncomingRequestReviewPage />)

    expect(await screen.findByRole('button', { name: 'Reassign' })).toBeInTheDocument()
    expect(screen.getByText(/the current coordinator loses access straight away/)).toBeInTheDocument()
    // Coordinator decisions are not the Lead's (AC21).
    expect(screen.queryByRole('button', { name: /^(Approve|Reject|Ask for clarification)$/ })).not.toBeInTheDocument()
  })

  it('ECL-C7 AC14 (boundary): a completed event can no longer be reassigned', async () => {
    seedSession('coordinator-lead')
    stubApi({
      'GET /api/event-requests/unassigned/r7': () => jsonResponse(200, {
        request: approvedRequest(status('COMPLETED', 'Completed', true)), missingFields: [], scheduleValid: true, timeline: [],
      }),
      'GET /api/event-requests/unassigned/coordinators': () => jsonResponse(200, []),
    })
    renderAt('/coordinator-lead/unassigned-requests/r7', '/coordinator-lead/unassigned-requests/:requestId',
      <IncomingRequestReviewPage />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Harbour Gala' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reassign' })).not.toBeInTheDocument()
  })

  it('ECL-C7 AC20, AC21 (cross-cutting): the Lead can view any event but is offered no coordinator actions', async () => {
    seedSession('coordinator-lead')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, eventDto),
      [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    })
    renderAt(`/coordinator-lead/events/${EVENT}`, '/coordinator-lead/events/:eventId', <EventDetailsPage />)

    expect(await screen.findByRole('heading', { name: 'Harbour Gala' })).toBeInTheDocument()
    expect(screen.getByText('ec1 (ec1@connectsphere.test)')).toBeInTheDocument()
    for (const name of [/Request a venue/, /Request equipment/, /Send for safety review/, /Confirm event/]) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })
})
