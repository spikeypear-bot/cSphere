import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import type { StatusView } from '../../types/status'
import type { EventReadinessDto, OrganiserArrangementsDto } from '../../types/event'
import { EventDetailsPage } from './EventDetailsPage'

const EVENT = 'ev-1'

function status(code: StatusView['code'], label: string, extra: Partial<StatusView> = {}): StatusView {
  return { code, label, explanation: `${label} explained.`, nextActor: 'Event Coordinator', stage: 'EVENT',
    isFinal: false, datePassed: false, ...extra }
}

const event = (journey: StatusView, apiStatus = 'pending') => ({
  eventId: EVENT, eventName: 'Town Hall', purpose: 'Update', description: null,
  startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 150,
  venueId: null, accessibilityNeeds: [], registrationNeeds: null, organisation: 'Acme Pte Ltd',
  venueRequirements: 'Theatre seating', equipmentRequirements: null, status: apiStatus,
  coordinatorName: 'ec1', coordinatorEmail: 'ec1@connectsphere.test', journeyStatus: journey,
})

function readiness(over: Partial<EventReadinessDto> = {}): EventReadinessDto {
  return {
    journeyStatus: status('PLANNING', 'Planning'),
    items: [
      { key: 'venues', label: 'Venue bookings', state: 'complete', detail: '1 venue booking approved.', actionPath: null },
      { key: 'equipment', label: 'Equipment', state: 'not_needed', detail: 'No equipment is needed.', actionPath: null },
      { key: 'safety', label: 'Operational Safety Check', state: 'outstanding', detail: 'Not sent yet.', actionPath: null },
    ],
    canSendForReview: true, canWithdraw: false, canConfirm: false, blockers: [], safetyFeedback: null,
    datePassed: false, ...over,
  }
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

describe('EC-NEW2 one consistent status on the event page', () => {
  it('shows the server label, its explanation and who acts next', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review',
      { explanation: 'The Safety Officer is checking the arrangements.', nextActor: 'Safety Officer' }), 'awaiting_safety_review')) })
    renderEvent('/organiser')

    expect(await screen.findByText('The Safety Officer is checking the arrangements.')).toBeInTheDocument()
    expect(screen.getByText(/Next: Safety Officer/)).toBeInTheDocument()
    expect(screen.getAllByText('Awaiting Safety Review').length).toBeGreaterThan(0)
    expect(screen.getByRole('list', { name: 'Progress: Awaiting Safety Review' })).toBeInTheDocument()
  })

  it('flags an event whose date passed without confirmation', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning', { datePassed: true }))) })
    renderEvent('/organiser')

    expect((await screen.findAllByText('Date passed')).length).toBeGreaterThan(0)
    expect(screen.getByText("This event's date has passed without it being confirmed.")).toBeInTheDocument()
  })
})

describe('EC08 readiness, send for safety review, withdraw and confirm', () => {
  it('lists each prerequisite with its state and sends the event for review', async () => {
    seedSession('coordinator')
    let sent = false
    const calls = stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning'))),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, sent
        ? readiness({ journeyStatus: status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'), canSendForReview: false, canWithdraw: true })
        : readiness()),
      [`POST /api/events/${EVENT}/safety-review`]: () => { sent = true; return jsonResponse(200, {}) },
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    expect(within(panel).getByText('1 venue booking approved.')).toBeInTheDocument()
    expect(within(panel).getByText('1 of 3 still to sort out.')).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Send for safety review' }))

    expect(await screen.findByText(/Sent for the Operational Safety Check/)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Withdraw from review' })).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.url === `/api/events/${EVENT}/safety-review`)).toBe(true)
  })

  it('explains why the event cannot be sent yet and links to the fix', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning'))),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        canSendForReview: false,
        blockers: ['1 venue has become unavailable. Request a replacement.'],
        items: [{ key: 'venues', label: 'Venue bookings', state: 'outstanding',
          detail: '1 venue has become unavailable. Request a replacement.', actionPath: `/coordinator/events/${EVENT}/venue-booking` }],
      })),
    })
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    expect(within(panel).getByText('Not ready yet')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'Resolve' })).toHaveAttribute('href', `/coordinator/events/${EVENT}/venue-booking`)
    expect(within(panel).queryByRole('button', { name: 'Send for safety review' })).not.toBeInTheDocument()
  })

  it('requires a reason to withdraw and sends it', async () => {
    seedSession('coordinator')
    const calls = stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'), 'awaiting_safety_review')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'), canSendForReview: false, canWithdraw: true })),
      [`POST /api/events/${EVENT}/safety-review/withdraw`]: () => jsonResponse(200, {}),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    await user.click(await screen.findByRole('button', { name: 'Withdraw from review' }))
    const submit = screen.getByRole('button', { name: 'Withdraw' })
    expect(submit).toBeDisabled()
    await user.type(screen.getByLabelText('Why are you withdrawing it?'), 'Attendance changed')
    await user.click(submit)

    expect(await screen.findByText(/back in Planning/)).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/withdraw'))?.body).toEqual({ reason: 'Attendance changed' })
  })

  it('shows the Safety Officer feedback when changes are requested', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('SAFETY_CHANGES_REQUESTED', 'Safety Changes Requested'), 'safety_changes_requested')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('SAFETY_CHANGES_REQUESTED', 'Safety Changes Requested'), safetyFeedback: 'Add a second exit marshal.' })),
    })
    renderEvent('/coordinator')

    expect(await screen.findByText('Add a second exit marshal.')).toBeInTheDocument()
    // Planning edits reopen while changes are requested.
    expect(screen.getByRole('link', { name: 'Request equipment' })).toBeInTheDocument()
  })

  it('confirms once the safety check is approved, and shows a server refusal plainly', async () => {
    seedSession('coordinator')
    let attempt = 0
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('SAFETY_APPROVED', 'Safety Approved'), 'safety_approved')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('SAFETY_APPROVED', 'Safety Approved'), canSendForReview: false, canConfirm: true })),
      [`POST /api/events/${EVENT}/confirm`]: () => (++attempt === 1
        ? jsonResponse(409, { message: 'This event is already confirmed.' })
        : jsonResponse(200, {})),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    await user.click(await within(panel).findByRole('button', { name: 'Confirm event' }))
    expect(await within(panel).findByRole('alert')).toHaveTextContent('This event is already confirmed.')
    await user.click(within(panel).getByRole('button', { name: 'Confirm event' }))
    expect(await screen.findByText(/Event confirmed\. The organiser and the staff involved have been notified\./)).toBeInTheDocument()
  })
})

function arrangements(over: Partial<OrganiserArrangementsDto> = {}): OrganiserArrangementsDto {
  return {
    eventId: EVENT, eventName: 'Town Hall', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
    expectedAttendance: 150, journeyStatus: status('CONFIRMED', 'Confirmed'), view: 'confirmed',
    coordinatorName: 'ec1', coordinatorEmail: 'ec1@connectsphere.test', accessibilityNeeds: ['step_free_access', 'elevators'],
    venues: [{ venueId: 'v1', name: 'Marina Hall', capacity: 200, layouts: ['theatre'], accessibilities: ['step_free_access'],
      facilities: ['projection'], operatingInformation: 'Loading bay at the back.', noLongerAvailable: false }],
    equipment: [{ name: 'Wireless microphone', quantity: 4 }],
    safetyApprovedAt: '2027-03-01T01:00:00Z', firstConfirmedAt: '2027-03-02T01:00:00Z', confirmedAt: '2027-03-02T01:00:00Z',
    ...over,
  }
}

function organiserStub(body: OrganiserArrangementsDto) {
  return stubApi({
    [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(body.journeyStatus, 'confirmed')),
    [`GET /api/events/${EVENT}/arrangements`]: () => jsonResponse(200, body),
  })
}

describe('EO04 organiser views confirmed arrangements', () => {
  it('shows venue, layout, facilities, equipment and how accessibility needs are met', async () => {
    seedSession('organiser')
    organiserStub(arrangements())
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByText('Marina Hall')).toBeInTheDocument()
    expect(within(card).getByText('Theatre')).toBeInTheDocument()
    expect(within(card).getByText('Projection')).toBeInTheDocument()
    expect(within(card).getByText('Wireless microphone')).toBeInTheDocument()
    expect(within(card).getByText('× 4')).toBeInTheDocument()
    expect(within(card).getByText(/not listed for this venue/)).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Contact ec1' })).toHaveAttribute('href', expect.stringContaining('mailto:ec1@connectsphere.test'))
    expect(within(card).getByRole('button', { name: 'Print summary' })).toBeInTheDocument()
  })

  it('says arrangements are being finalised before confirmation, without showing any', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ view: 'not_confirmed', journeyStatus: status('PLANNING', 'Planning'), venues: [], equipment: [] }))
    renderEvent('/organiser')

    expect(await screen.findByText(/Your arrangements are being finalised/)).toBeInTheDocument()
    expect(screen.queryByText('Marina Hall')).not.toBeInTheDocument()
  })

  it('flags a venue that is no longer available and that arrangements are being updated', async () => {
    seedSession('organiser')
    organiserStub(arrangements({
      view: 'reconfirming', journeyStatus: status('PLANNING', 'Planning'),
      venues: [{ ...arrangements().venues[0], noLongerAvailable: true }],
    }))
    renderEvent('/organiser')

    expect(await screen.findByText('Arrangements are being updated.')).toBeInTheDocument()
    expect(screen.getByText('No longer available')).toBeInTheDocument()
  })

  it('marks arrangements updated after the first confirmation', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ confirmedAt: '2027-03-05T01:00:00Z' }))
    renderEvent('/organiser')

    expect(await screen.findByText(/Updated since first confirmed/)).toBeInTheDocument()
  })
})
