// @stories EO07
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { StatusView } from '../../types/status'
import type { EventCancellationRequestDto } from '../../types/eventCancellation'
import { EventDetailsPage } from './EventDetailsPage'

const EVENT = 'ev-1'

const JOURNEY: Record<string, [StatusView['code'], string]> = {
  pending: ['PLANNING', 'Planning'],
  awaiting_safety_review: ['AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'],
  safety_changes_requested: ['SAFETY_CHANGES_REQUESTED', 'Safety Changes Requested'],
  safety_approved: ['SAFETY_APPROVED', 'Safety Approved'],
  confirmed: ['CONFIRMED', 'Confirmed'],
  cancelled: ['CANCELLED', 'Cancelled'],
  completed: ['COMPLETED', 'Completed'],
}

function event(apiStatus: string, journeyCode?: StatusView['code']) {
  const [code, label] = JOURNEY[apiStatus]
  const finalCode = journeyCode ?? code
  return {
    eventId: EVENT, eventName: 'Town Hall', purpose: 'Update', description: null,
    startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 150,
    venueId: null, accessibilityNeeds: [], registrationNeeds: null, organisation: 'Acme Pte Ltd',
    venueRequirements: 'Theatre seating', equipmentRequirements: null, status: apiStatus,
    coordinatorName: 'ec1', coordinatorEmail: 'ec1@connectsphere.test',
    journeyStatus: { code: finalCode, label: journeyCode === 'COMPLETED' ? 'Completed' : label,
      explanation: `${label}.`, nextActor: null, stage: 'EVENT',
      isFinal: ['CANCELLED', 'COMPLETED'].includes(finalCode), datePassed: false },
  }
}

function cancellation(over: Partial<EventCancellationRequestDto> = {}): EventCancellationRequestDto {
  return {
    cancellationRequestId: 'can-1', eventId: EVENT, eventName: 'Town Hall', requesterName: 'eo1',
    reason: 'The client has called the event off.', status: 'pending', requestedAt: '2026-10-20T02:30:00Z',
    decidedByName: null, decidedAt: null, decisionReason: null, ...over,
  }
}

/** Everything the organiser's event page loads, with the event's cancellation requests. */
function organiserPage(apiStatus: string, requests: () => EventCancellationRequestDto[],
  extra: Record<string, (body: unknown) => Response> = {}, journeyCode?: StatusView['code']) {
  return stubApi({
    [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(apiStatus, journeyCode)),
    [`GET /api/events/${EVENT}/arrangements`]: () => jsonResponse(200, { view: 'not_confirmed', venues: [] }),
    [`GET /api/events/${EVENT}/timeline`]: () => jsonResponse(200, []),
    [`GET /api/events/${EVENT}/cancellation-requests`]: () => jsonResponse(200, requests()),
    ...extra,
  })
}

function renderEvent(base: string) {
  return render(
    <MemoryRouter initialEntries={[`${base}/events/${EVENT}`]}>
      <SessionProvider>
        <Routes><Route path={`${base}/events/:eventId`} element={<EventDetailsPage />} /></Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('EO07 organiser requests cancellation of an event', () => {
  it.each(['pending', 'awaiting_safety_review', 'safety_changes_requested', 'safety_approved', 'confirmed'])(
    'EO07 AC3 (happy): an event in %s offers "Request cancellation"', async (apiStatus) => {
      seedSession('organiser')
      organiserPage(apiStatus, () => [])
      renderEvent('/organiser')

      expect(await screen.findByRole('button', { name: 'Request cancellation' })).toBeInTheDocument()
    })

  it.each(['cancelled', 'completed'])(
    'EO07 AC5 (negative): a %s event offers no "Request cancellation"', async (apiStatus) => {
      seedSession('organiser')
      organiserPage(apiStatus, () => [])
      renderEvent('/organiser')

      expect(await screen.findByRole('heading', { name: 'Town Hall' })).toBeInTheDocument()
      await screen.findByText('No changes recorded yet.')
      expect(screen.queryByRole('button', { name: 'Request cancellation' })).not.toBeInTheDocument()
    })

  it('EO07 AC5 (boundary): a Confirmed event whose end has passed is Completed and offers no "Request cancellation"', async () => {
    seedSession('organiser')
    organiserPage('confirmed', () => [], {}, 'COMPLETED')
    renderEvent('/organiser')

    await screen.findByText('No changes recorded yet.')
    expect(screen.queryByRole('button', { name: 'Request cancellation' })).not.toBeInTheDocument()
  })

  it('EO07 AC6 (happy): while a request is pending, the organiser sees their reason and when they asked instead of the button', async () => {
    seedSession('organiser')
    organiserPage('confirmed', () => [cancellation()])
    renderEvent('/organiser')

    expect(await screen.findByRole('heading', { name: 'Cancellation requested' })).toBeInTheDocument()
    expect(screen.getByText("Awaiting the coordinator's decision. The event stays as it is until then."))
      .toBeInTheDocument()
    expect(screen.getByText('The client has called the event off.')).toBeInTheDocument()
    expect(screen.getByText(/Requested 20 Oct 2026/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request cancellation' })).not.toBeInTheDocument()
  })

  it('EO07 AC4 (negative): an empty reason is refused with a message and nothing is sent', async () => {
    seedSession('organiser')
    const calls = organiserPage('confirmed', () => [])
    const user = userEvent.setup()
    renderEvent('/organiser')

    await user.click(await screen.findByRole('button', { name: 'Request cancellation' }))
    await user.click(screen.getByRole('button', { name: 'Send cancellation request' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Please give a reason for cancelling this event.')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('EO07 AC3, AC6 (happy): sending the reason creates the request on the organiser\'s own request and shows it as awaiting a decision', async () => {
    seedSession('organiser')
    let requested = false
    const calls = organiserPage('confirmed', () => (requested ? [cancellation({ reason: 'Venue flooded' })] : []), {
      'GET /api/event-requests': () => jsonResponse(200, [
        { requestId: 'other-req', eventId: 'other-event' }, { requestId: 'req-1', eventId: EVENT }]),
      'POST /api/event-requests/req-1/cancellation-request': () => {
        requested = true
        return jsonResponse(200, cancellation({ reason: 'Venue flooded' }))
      },
    })
    const user = userEvent.setup()
    renderEvent('/organiser')

    await user.click(await screen.findByRole('button', { name: 'Request cancellation' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), ' Venue flooded ')
    await user.click(screen.getByRole('button', { name: 'Send cancellation request' }))

    expect(await screen.findByRole('heading', { name: 'Cancellation requested' })).toBeInTheDocument()
    expect(screen.getByText('Venue flooded')).toBeInTheDocument()
    const post = calls.find((c) => c.method === 'POST')
    expect(post?.url).toBe('/api/event-requests/req-1/cancellation-request')
    expect(post?.body).toEqual({ reason: 'Venue flooded' })
    // AC3: the event keeps its status until the coordinator decides.
    expect(screen.getAllByText('Confirmed').length).toBeGreaterThan(0)
  })

  it('EO07 AC14 (failure): a server refusal is shown and the organiser can try again', async () => {
    seedSession('organiser')
    organiserPage('confirmed', () => [], {
      'GET /api/event-requests': () => jsonResponse(200, [{ requestId: 'req-1', eventId: EVENT }]),
      'POST /api/event-requests/req-1/cancellation-request': () => jsonResponse(409,
        { message: "A cancellation request for this event is already waiting for the coordinator's decision." }),
    })
    const user = userEvent.setup()
    renderEvent('/organiser')

    await user.click(await screen.findByRole('button', { name: 'Request cancellation' }))
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Called off')
    await user.click(screen.getByRole('button', { name: 'Send cancellation request' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already waiting for the coordinator')
    expect(screen.getByRole('button', { name: 'Send cancellation request' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: 'Cancellation requested' })).not.toBeInTheDocument()
  })

  it('EO07 AC9, AC10 (happy): after a decline the organiser sees the coordinator\'s reason and can ask again', async () => {
    seedSession('organiser')
    organiserPage('confirmed', () => [cancellation({ status: 'declined', decidedByName: 'ec1',
      decidedAt: '2026-10-21T02:00:00Z', decisionReason: 'The venue deposit is non-refundable.' })])
    renderEvent('/organiser')

    expect(await screen.findByText('Your last cancellation request was declined: The venue deposit is non-refundable.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request cancellation' })).toBeInTheDocument()
  })
})
