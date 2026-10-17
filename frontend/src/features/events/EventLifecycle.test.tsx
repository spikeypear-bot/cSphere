import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
  it('EC-NEW2 AC2, AC18, AC19 (happy): shows the server label, its explanation and who acts next', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review',
      { explanation: 'The Safety Officer is checking the arrangements.', nextActor: 'Safety Officer' }), 'awaiting_safety_review')) })
    renderEvent('/organiser')

    expect(await screen.findByText('The Safety Officer is checking the arrangements.')).toBeInTheDocument()
    expect(screen.getByText(/Next: Safety Officer/)).toBeInTheDocument()
    expect(screen.getAllByText('Awaiting Safety Review').length).toBeGreaterThan(0)
    expect(screen.getByRole('list', { name: 'Progress: Awaiting Safety Review' })).toBeInTheDocument()
  })

  it('EC-NEW2 AC14 (boundary): flags an event whose date passed without confirmation', async () => {
    seedSession('organiser')
    stubApi({ [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning', { datePassed: true }))) })
    renderEvent('/organiser')

    expect((await screen.findAllByText('Date passed')).length).toBeGreaterThan(0)
    expect(screen.getByText("This event's date has passed without it being confirmed.")).toBeInTheDocument()
  })
})

describe('EC08 readiness, send for safety review, withdraw and confirm', () => {
  it('EC08 AC3, AC16, EC-NEW2 AC21 (happy): lists each prerequisite with its state and sends the event for review', async () => {
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

  it('EC08 AC4, AC15 (negative): explains why the event cannot be sent yet and links to the fix', async () => {
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

  it('EC08 AC19 (negative): requires a reason to withdraw and sends it', async () => {
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

  it('EC08 AC23, AC24 (happy): shows the Safety Officer feedback when changes are requested', async () => {
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

  it('EC08 AC29, AC36, EC-NEW2 AC23 (conflict): confirms once the safety check is approved, and shows a server refusal plainly', async () => {
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

  it('EC08 AC5, AC27 (conflict): says what changed when a prerequisite stopped holding before Confirm, and refreshes the checklist', async () => {
    seedSession('coordinator')
    let refused = false
    let lifecycleLoads = 0
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('SAFETY_APPROVED', 'Safety Approved'), 'safety_approved')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => {
        lifecycleLoads += 1
        return jsonResponse(200, refused
          ? readiness({ journeyStatus: status('SAFETY_APPROVED', 'Safety Approved'), canSendForReview: false, canConfirm: false,
            blockers: ['1 booking is still waiting for Venue Staff.'] })
          : readiness({ journeyStatus: status('SAFETY_APPROVED', 'Safety Approved'), canSendForReview: false, canConfirm: true }))
      },
      [`POST /api/events/${EVENT}/confirm`]: () => {
        refused = true
        return jsonResponse(422, { message: 'This event can no longer be confirmed as it stands.',
          missingFields: ['1 booking is still waiting for Venue Staff.'] })
      },
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    await user.click(await within(panel).findByRole('button', { name: 'Confirm event' }))
    const alert = await within(panel).findByRole('alert')
    expect(alert).toHaveTextContent('This event can no longer be confirmed as it stands.')
    expect(alert).toHaveTextContent('1 booking is still waiting for Venue Staff.')
    await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Confirm event' })).not.toBeInTheDocument())
    expect(lifecycleLoads).toBeGreaterThan(1)
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
  it('EO04 AC12, AC13, AC15, AC16 (happy): shows venue, layout, facilities, equipment and how accessibility needs are met', async () => {
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

  it("EO04 AC12, AC15 (happy): lists every accessibility feature the venue provides, not only the organiser's needs", async () => {
    seedSession('organiser')
    organiserStub(arrangements({ accessibilityNeeds: ['step_free_access'], venues: [{ ...arrangements().venues[0],
      accessibilities: ['step_free_access', 'elevators', 'accessible_parking'] }] }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    const facts = within(card).getByText('Accessibility at this venue').closest('div') as HTMLElement
    expect(facts).toHaveTextContent(/Step-free access, Elevators?, Accessible parking/i)
  })

  it('EO04 AC28 (failure): says when the arrangements cannot be loaded and loads them on Try again', async () => {
    seedSession('organiser')
    let calls = 0
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('CONFIRMED', 'Confirmed'), 'confirmed')),
      [`GET /api/events/${EVENT}/arrangements`]: () => (++calls === 1
        ? jsonResponse(500, { message: 'Server error' })
        : jsonResponse(200, arrangements())),
    })
    const user = userEvent.setup()
    renderEvent('/organiser')

    expect(await screen.findByText(/could not load your event arrangements/i)).toBeInTheDocument()
    // The change log has its own Try again (EC-NEW5); this one belongs to the arrangements.
    const card = screen.getByRole('region', { name: 'Event arrangements' })
    await user.click(within(card).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('region', { name: 'Confirmed arrangements' })).toBeInTheDocument()
  })

  it('EO04 AC9, AC10 (happy): shows times in Singapore time and says so', async () => {
    seedSession('organiser')
    organiserStub(arrangements())
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    // 01:00Z is 9:00 am in Singapore, whatever the browser's own timezone.
    expect(card).toHaveTextContent(/10 Mar 2027, 0?9:00 am – 10 Mar 2027, 12:00 pm \(Singapore time\)/i)
    expect(screen.getByText('Start (Singapore time)')).toBeInTheDocument()
  })

  it('EO04 AC6, AC8 (negative): says arrangements are being finalised before confirmation, without showing any', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ view: 'not_confirmed', journeyStatus: status('PLANNING', 'Planning'), venues: [], equipment: [] }))
    renderEvent('/organiser')

    expect(await screen.findByText(/Your arrangements are being finalised/)).toBeInTheDocument()
    expect(screen.queryByText('Marina Hall')).not.toBeInTheDocument()
  })

  it('EO04 AC21, AC22 (conflict): flags a venue that is no longer available and that arrangements are being updated', async () => {
    seedSession('organiser')
    organiserStub(arrangements({
      view: 'reconfirming', journeyStatus: status('PLANNING', 'Planning'),
      venues: [{ ...arrangements().venues[0], noLongerAvailable: true }],
    }))
    renderEvent('/organiser')

    expect(await screen.findByText('Arrangements are being updated.')).toBeInTheDocument()
    expect(screen.getByText('No longer available')).toBeInTheDocument()
  })

  it('EO04 AC11 (boundary): an event spanning two days shows both its start and end dates', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-11T04:00:00Z' }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(card).toHaveTextContent(/10 Mar 2027.*–.*11 Mar 2027/)
  })

  it('EO04 AC14 (boundary): says no equipment was arranged instead of showing an empty list', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ equipment: [] }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByText('No equipment was arranged for this event.')).toBeInTheDocument()
  })

  it('EO04 AC24 (failure): a cancelled event says so and shows no arrangements as if they still applied', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ view: 'cancelled', journeyStatus: status('CANCELLED', 'Cancelled'), venues: [], equipment: [] }))
    renderEvent('/organiser')

    expect(await screen.findByText('This event was cancelled, so there are no arrangements to show.')).toBeInTheDocument()
    expect(screen.queryByText('Marina Hall')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Print summary' })).not.toBeInTheDocument()
  })

  it('EO04 AC25 (boundary): a completed event keeps its arrangements as a record, marked Completed', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ view: 'completed', journeyStatus: status('COMPLETED', 'Completed') }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByRole('status')).toHaveTextContent('Completed. This event has taken place')
    expect(within(card).getByText('Marina Hall')).toBeInTheDocument()
  })

  it('EO04 AC23 (happy): marks arrangements updated after the first confirmation', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ confirmedAt: '2027-03-05T01:00:00Z' }))
    renderEvent('/organiser')

    expect(await screen.findByText(/Updated since first confirmed/)).toBeInTheDocument()
  })
})

describe('EC08 / EO04 / EC-NEW2 edge cases (boundary, failure)', () => {
  it('EC08 AC36, AC37 (failure): an unreachable server while sending is reported, and nothing is shown as sent', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning'))),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness()),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')
    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    // The network itself fails on the POST: fetch rejects, as when offline.
    const realFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') throw new TypeError('Failed to fetch')
      return realFetch(input, init)
    }))

    await user.click(within(panel).getByRole('button', { name: 'Send for safety review' }))

    expect(await within(panel).findByRole('alert')).toHaveTextContent('Could not reach ConnectSphere')
    expect(within(panel).queryByText(/Sent for the Operational Safety Check/)).not.toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Send for safety review' })).toBeEnabled()
  })

  it('EC08 AC3 (boundary): with every prerequisite met the checklist says everything needed is in place', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('SAFETY_APPROVED', 'Safety Approved'), 'safety_approved')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('SAFETY_APPROVED', 'Safety Approved'), canSendForReview: false, canConfirm: true,
        items: readiness().items.map((i) => ({ ...i, state: i.key === 'equipment' ? 'not_needed' as const : 'complete' as const })),
      })),
    })
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Ready for confirmation?' })
    expect(within(panel).getByText('Everything needed is in place.')).toBeInTheDocument()
  })

  it('EC-NEW2 AC29 (negative): once confirmed the panel is a read-only record with no lifecycle actions', async () => {
    seedSession('coordinator')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('CONFIRMED', 'Confirmed'), 'confirmed')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('CONFIRMED', 'Confirmed'), canSendForReview: false })),
    })
    renderEvent('/coordinator')

    const panel = await screen.findByRole('region', { name: 'Arrangements' })
    expect(within(panel).queryByText(/still to sort out|Everything needed/)).not.toBeInTheDocument()
    for (const name of ['Send for safety review', 'Confirm event', 'Withdraw from review']) {
      expect(within(panel).queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })

  it('EC08 AC19 (negative): choosing Keep in review closes the form and sends nothing', async () => {
    seedSession('coordinator')
    const calls = stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'), 'awaiting_safety_review')),
      [`GET /api/events/${EVENT}/lifecycle`]: () => jsonResponse(200, readiness({
        journeyStatus: status('AWAITING_SAFETY_REVIEW', 'Awaiting Safety Review'), canSendForReview: false, canWithdraw: true })),
    })
    const user = userEvent.setup()
    renderEvent('/coordinator')

    await user.click(await screen.findByRole('button', { name: 'Withdraw from review' }))
    await user.type(screen.getByLabelText('Why are you withdrawing it?'), 'Changed my mind')
    await user.click(screen.getByRole('button', { name: 'Keep in review' }))

    expect(screen.queryByLabelText('Why are you withdrawing it?')).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('EO04 AC12, AC16, AC17 (boundary): optional details that are absent are left out, never shown blank', async () => {
    seedSession('organiser')
    organiserStub(arrangements({
      coordinatorEmail: null, safetyApprovedAt: null, confirmedAt: null, firstConfirmedAt: null,
      venues: [{ ...arrangements().venues[0], capacity: null, layouts: [], facilities: [], accessibilities: [],
        operatingInformation: null }],
    }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    for (const label of ['Capacity', 'Room layout', 'Facilities', 'Good to know', 'Safety check approved', 'Confirmed']) {
      expect(within(card).queryByText(label, { selector: 'dt' })).not.toBeInTheDocument()
    }
    expect(within(card).getByText(/None listed\. Ask your coordinator/)).toBeInTheDocument()
    // No email: the coordinator is named, without a broken mail link.
    expect(within(card).queryByRole('link', { name: /Contact/ })).not.toBeInTheDocument()
    expect(within(card).getByText('ec1')).toBeInTheDocument()
  })

  it('EO04 AC16 (boundary): with no coordinator name the contact link still reads sensibly', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ coordinatorName: null }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByRole('link', { name: 'Contact your coordinator' })).toBeInTheDocument()
  })

  it('EO04 AC12 (boundary): several venues are listed under a plural heading, and none says so', async () => {
    seedSession('organiser')
    const second = { ...arrangements().venues[0], venueId: 'v2', name: 'Harbour Room' }
    organiserStub(arrangements({ venues: [arrangements().venues[0], second] }))
    const { unmount } = renderEvent('/organiser')
    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByRole('heading', { name: 'Venues' })).toBeInTheDocument()
    expect(within(card).getByText('Harbour Room')).toBeInTheDocument()
    unmount()

    organiserStub(arrangements({ venues: [] }))
    renderEvent('/organiser')
    expect(await screen.findByText('No venue is booked.')).toBeInTheDocument()
  })

  it('EO04 AC12 (boundary): a value the page has no label for yet is still shown readably', async () => {
    seedSession('organiser')
    organiserStub(arrangements({
      accessibilityNeeds: ['sign_language'],
      venues: [{ ...arrangements().venues[0], layouts: ['cabaret_style'], facilities: ['green_room'],
        accessibilities: ['sign_language'] }],
    }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(card).toHaveTextContent('cabaret style')
    expect(card).toHaveTextContent('green room')
    expect(card).toHaveTextContent('sign language')
  })

  it('EO04 AC26 (happy): Print summary opens the browser print dialog', async () => {
    seedSession('organiser')
    organiserStub(arrangements())
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    const user = userEvent.setup()
    renderEvent('/organiser')

    await user.click(await screen.findByRole('button', { name: 'Print summary' }))
    expect(print).toHaveBeenCalledTimes(1)
    print.mockRestore()
  })

  it('EC08 AC5, EO04 AC28 (boundary): leaving the page before data arrives updates nothing afterwards', async () => {
    seedSession('organiser')
    let release: (r: Response) => void = () => {}
    const pending = new Promise<Response>((resolve) => { release = resolve })
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('CONFIRMED', 'Confirmed'), 'confirmed')),
      [`GET /api/events/${EVENT}/arrangements`]: () => pending as unknown as Response,
    })
    const error = vi.spyOn(console, 'error')
    const { unmount } = renderEvent('/organiser')
    await screen.findByRole('heading', { name: 'Town Hall' })

    unmount()
    release(jsonResponse(200, arrangements()))
    await waitFor(() => expect(error).not.toHaveBeenCalled())
    error.mockRestore()
  })

  it('EO04 AC15 (boundary): with no accessibility needs stated, no needs comparison is shown', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ accessibilityNeeds: ['none'] }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).queryByText('Your accessibility needs')).not.toBeInTheDocument()
    expect(within(card).getByText('Marina Hall')).toBeInTheDocument()
  })

  it('EO04 AC16 (boundary): with no coordinator name or email the organiser is pointed to their Event Coordinator', async () => {
    seedSession('organiser')
    organiserStub(arrangements({ coordinatorName: null, coordinatorEmail: null }))
    renderEvent('/organiser')

    const card = await screen.findByRole('region', { name: 'Confirmed arrangements' })
    expect(within(card).getByText('Your Event Coordinator')).toBeInTheDocument()
  })

  it('EC08 AC5, EO04 AC28 (boundary): a load that fails after leaving the page shows nothing afterwards', async () => {
    let failArrangements: (r: Response) => void = () => {}
    let failLifecycle: (r: Response) => void = () => {}
    const error = vi.spyOn(console, 'error')

    seedSession('organiser')
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('CONFIRMED', 'Confirmed'), 'confirmed')),
      [`GET /api/events/${EVENT}/arrangements`]: () =>
        new Promise<Response>((resolve) => { failArrangements = resolve }) as unknown as Response,
    })
    const organiserPage = renderEvent('/organiser')
    await screen.findByRole('heading', { name: 'Town Hall' })
    organiserPage.unmount()
    failArrangements(jsonResponse(500, { message: 'Server error' }))

    window.localStorage.clear()
    seedSession('coordinator')
    let resolveLifecycle: (r: Response) => void = () => {}
    stubApi({
      [`GET /api/events/${EVENT}`]: () => jsonResponse(200, event(status('PLANNING', 'Planning'))),
      [`GET /api/events/${EVENT}/lifecycle`]: () =>
        new Promise<Response>((resolve) => { resolveLifecycle = resolve; failLifecycle = resolve }) as unknown as Response,
    })
    const coordinatorPage = renderEvent('/coordinator')
    await screen.findByRole('heading', { name: 'Town Hall' })
    coordinatorPage.unmount()
    resolveLifecycle(jsonResponse(200, readiness()))

    const second = renderEvent('/coordinator')
    await screen.findByRole('heading', { name: 'Town Hall' })
    second.unmount()
    failLifecycle(jsonResponse(500, { message: 'Server error' }))

    await waitFor(() => expect(error).not.toHaveBeenCalled())
    error.mockRestore()
  })
})

