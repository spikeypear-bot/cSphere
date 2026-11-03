// @stories EO03  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ChangeRequestFormPage } from './ChangeRequestFormPage'

const REQ = 'req-1'
const EVENT = 'event-1'
const CATALOGUE = [{ equipmentId: 'mixer', equipmentName: 'Audio Mixer' }, { equipmentId: 'mic', equipmentName: 'Microphone' }]

/** An approved request: 10 Mar 2027, 09:00-12:00 Singapore time, 150 people. */
function request(extra: Record<string, unknown> = {}) {
  return {
    requestId: REQ, requestType: 'C', eventId: EVENT, eventName: 'Town Hall', purpose: 'Quarterly update',
    description: 'All hands', startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
    expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null, equipmentItems: [],
    accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: false, status: 'approved',
    createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-21T01:00:00Z', organisation: 'Acme Pte Ltd',
    journeyStatus: { code: 'PLANNING', label: 'Planning', explanation: '', nextActor: null, stage: 'EVENT', isFinal: false, datePassed: false },
    ...extra,
  }
}

function routes(extra: Record<string, (body: unknown) => Response> = {}, found = request()) {
  return {
    [`GET /api/event-requests/${REQ}`]: () => jsonResponse(200, found),
    [`GET /api/change-requests?requestId=${REQ}`]: () => jsonResponse(200, []),
    'GET /api/equipment/catalogue': () => jsonResponse(200, CATALOGUE),
    ...extra,
  }
}

const unreadable = () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response

function renderAt(path = `/organiser/requests/${REQ}/change`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/organiser/requests/:requestId/change" element={<ChangeRequestFormPage />} />
          <Route path="/organiser/events/:eventId/change" element={<ChangeRequestFormPage />} />
          <Route path="/organiser/requests/:requestId/changes" element={<p>Change requests list</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function toReview(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Review the change' }))
  return screen.getByRole('region', { name: 'Current and proposed details' })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

describe('EO03 — the change request form, field by field', () => {
  it('EO03 (happy): opened from the event, every text, accessibility and facility change is listed for review and kept on Back', async () => {
    seedSession('organiser')
    stubApi(routes({
      'GET /api/event-requests': () => jsonResponse(200, [request({ requestId: 'other', eventId: 'other-event' }), request()]),
    }))
    const user = userEvent.setup()
    renderAt(`/organiser/events/${EVENT}/change`)

    fireEvent.change(await screen.findByLabelText('Purpose'), { target: { value: 'Annual update' } })
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Everyone' } })
    fireEvent.change(screen.getByLabelText('Venue requirements'), { target: { value: 'Banquet' } })
    fireEvent.change(screen.getByLabelText('Additional equipment notes (optional)'), { target: { value: 'Two lecterns' } })
    fireEvent.click(screen.getByRole('button', { name: 'Wheelchair support' }))
    expect(screen.getByRole('button', { name: 'No accessibility requirements needed' })).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByLabelText('Stage'))
    fireEvent.click(screen.getByLabelText('Projection'))
    fireEvent.click(screen.getByLabelText('Stage'))
    expect(screen.getByRole('link', { name: 'Change requests for this event' })).toHaveAttribute('href', `/organiser/requests/${REQ}/changes`)

    const review = await toReview(user)
    const row = (label: string) => within(review).getByRole('row', { name: new RegExp(`^${label}`) })
    expect(row('Purpose')).toHaveTextContent('Quarterly updateAnnual update')
    expect(row('Description')).toHaveTextContent('All handsEveryone')
    expect(row('Venue requirements')).toHaveTextContent('Theatre seatingBanquet')
    expect(row('Equipment requirements')).toHaveTextContent('Two lecterns')
    expect(row('Accessibility needs')).toHaveTextContent('Wheelchair support')
    expect(row('Required facilities')).toHaveTextContent('—Projection')

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByLabelText('Purpose')).toHaveValue('Annual update')
    // Choosing "none" again replaces the real accommodation.
    fireEvent.click(screen.getByRole('button', { name: 'No accessibility requirements needed' }))
    expect(screen.getByRole('button', { name: 'Wheelchair support' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('EO03 (negative): opened from an event that is not among their requests, the organiser is told they cannot change it', async () => {
    seedSession('organiser')
    stubApi(routes({ 'GET /api/event-requests': () => jsonResponse(200, [request({ eventId: 'other-event' })]) }))
    renderAt(`/organiser/events/${EVENT}/change`)

    expect(await screen.findByText(/You can't request a change to this event/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('EO03 (failure): an unreadable answer for the event shows a general message with Try again', async () => {
    seedSession('organiser')
    stubApi(routes({ [`GET /api/event-requests/${REQ}`]: unreadable }))
    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent('Your event could not be loaded.')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('EO03 (boundary): a request with blank details names what is required before review, and unnamed events read "Your event"', async () => {
    seedSession('organiser')
    stubApi(routes({}, request({
      eventName: null, purpose: null, description: null, venueRequirements: null,
      accessibilityNeeds: null, requiredFacilities: null, equipmentItems: null,
    })))
    renderAt()

    expect(await screen.findByText('Your event · nothing changes until your coordinator approves it')).toBeInTheDocument()
    expect(screen.getByText('Event name is required')).toBeInTheDocument()
    expect(screen.getByText('Purpose is required')).toBeInTheDocument()
    expect(screen.getByText('Venue requirements is required')).toBeInTheDocument()
    expect(screen.getByLabelText('Description')).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
  })

  it('EO03 (happy): equipment added, raised or removed is shown by name, and an item no longer in the catalogue by its id', async () => {
    seedSession('organiser')
    stubApi(routes({}, request({ equipmentItems: [{ equipmentId: 'mixer', quantity: 2 }, { equipmentId: 'retired-lamp', quantity: 1 }] })))
    const user = userEvent.setup()
    renderAt()

    await screen.findByRole('spinbutton', { name: 'Audio Mixer quantity' })
    fireEvent.click(screen.getByRole('button', { name: 'Add or remove equipment' }))
    fireEvent.click(screen.getByRole('button', { name: 'Increase Microphone quantity' }))
    fireEvent.click(screen.getByRole('button', { name: 'Increase Audio Mixer quantity' }))
    fireEvent.click(screen.getByRole('button', { name: 'Decrease retired-lamp quantity' }))

    const review = await toReview(user)
    const row = within(review).getByRole('row', { name: /^Equipment items and quantities/ })
    expect(row).toHaveTextContent('2 × Audio Mixer, 1 × retired-lamp')
    expect(row).toHaveTextContent('3 × Audio Mixer, 1 × Microphone')
    expect(row).not.toHaveTextContent('3 × Audio Mixer, 1 × Microphone, 1 × retired-lamp')
  })

  it('EO03 (failure): when the equipment catalogue cannot load the organiser is told and can retry it', async () => {
    seedSession('organiser')
    let attempts = 0
    stubApi(routes({
      'GET /api/equipment/catalogue': () => (++attempts === 1 ? jsonResponse(500, {}) : jsonResponse(200, CATALOGUE)),
    }))
    const user = userEvent.setup()
    renderAt()

    expect(await screen.findByText('The equipment catalogue could not be loaded.', { exact: false })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry catalogue' }))
    fireEvent.click(screen.getByRole('button', { name: 'Add or remove equipment' }))

    expect(await screen.findByRole('spinbutton', { name: 'Microphone quantity' })).toBeInTheDocument()
    expect(screen.queryByText('The equipment catalogue could not be loaded.', { exact: false })).not.toBeInTheDocument()
  })

  it('EO03 (happy): turning registration on offers its settings, and what is entered is reviewed and sent', async () => {
    seedSession('organiser')
    const calls = stubApi(routes({ 'POST /api/change-requests': () => jsonResponse(201, {}) }))
    const user = userEvent.setup()
    renderAt()

    expect(screen.queryByLabelText('Registration capacity')).not.toBeInTheDocument()
    await user.click(await screen.findByLabelText('Attendees need to register'))
    fireEvent.change(screen.getByLabelText('Registration capacity'), { target: { value: '120' } })
    fireEvent.change(screen.getByLabelText('Registration opens'), { target: { value: '2027-03-01T09:00' } })
    fireEvent.change(screen.getByLabelText('Registration closes'), { target: { value: '2027-03-09T18:00' } })
    fireEvent.change(screen.getByLabelText('Attendee instructions'), { target: { value: 'Bring ID' } })
    fireEvent.change(screen.getByLabelText('Attendee instructions'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Attendee instructions'), { target: { value: 'Bring ID' } })

    const review = await toReview(user)
    expect(within(review).getByRole('row', { name: /^Registration capacity/ })).toHaveTextContent('120')
    expect(within(review).getByRole('row', { name: /^Attendee instructions/ })).toHaveTextContent('Bring ID')
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'Ticketed now' } })
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByText('Change requests list')).toBeInTheDocument()
    expect((calls.find((c) => c.method === 'POST')?.body as { details: Record<string, unknown> }).details).toMatchObject({
      registrationNeeds: true, registrationCapacity: 120, registrationOpensAt: '2027-03-01T01:00:00.000Z',
      registrationClosesAt: '2027-03-09T10:00:00.000Z', attendeeInstructions: 'Bring ID',
    })
  })

  it('EO03 (negative): registration cannot be turned off while attendees are confirmed', async () => {
    seedSession('organiser')
    stubApi(routes({ 'GET /api/events/event-1/registrations': () => jsonResponse(200, { confirmedCount: 4 }) }, request({
      registrationNeeds: true, registrationCapacity: 150, registrationOpensAt: '2027-03-01T01:00:00Z',
    })))
    const user = userEvent.setup()
    renderAt()
    await user.click(await screen.findByLabelText('Attendees need to register'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Capacity cannot be lower than the 4 active confirmed registration(s)')
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
  })

  it('EO03 (failure): when the registration count cannot be read the organiser is told the server will check, and can retry', async () => {
    seedSession('organiser')
    let attempts = 0
    stubApi(routes({
      'GET /api/events/event-1/registrations': () => (++attempts === 1 ? jsonResponse(500, {}) : jsonResponse(200, { confirmedCount: 0 })),
    }, request({ registrationNeeds: true, registrationCapacity: 150, registrationOpensAt: '2027-03-01T01:00:00Z' })))
    const user = userEvent.setup()
    renderAt()

    expect(await screen.findByText(/current registration count could not be retrieved/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry registration count' }))
    await waitFor(() => expect(screen.queryByText(/current registration count could not be retrieved/)).not.toBeInTheDocument())
  })

  it('EO03 (failure): fields the server reports missing or refused are shown beside them, and clear once changed', async () => {
    seedSession('organiser')
    stubApi(routes({
      'POST /api/change-requests': () => jsonResponse(422, {
        message: 'Some details need attention.', missingFields: ['venueRequirements', 'organisationContact'],
        fieldErrors: { equipmentItems: 'Microphone has only 10 available.' },
      }),
    }))
    const user = userEvent.setup()
    renderAt()
    fireEvent.change(await screen.findByLabelText('Event name'), { target: { value: 'Town Hall 2027' } })
    await toReview(user)
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'Rename' } })
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByText('Venue requirements is required')).toBeInTheDocument()
    expect(screen.getByText('Some details need attention.')).toBeInTheDocument()
    expect(screen.getByText('Microphone has only 10 available.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Venue requirements'), { target: { value: 'Theatre seating, stage' } })
    expect(screen.queryByText('Venue requirements is required')).not.toBeInTheDocument()
    expect(screen.getByText('Microphone has only 10 available.')).toBeInTheDocument()
  })

  it('EO03 (failure): when sending fails without field details the organiser stays on the review with the reason, and Sending… shows meanwhile', async () => {
    seedSession('organiser')
    let answer: (r: Response) => void = () => {}
    stubApi(routes({
      'POST /api/change-requests': () => new Promise<Response>((resolve) => { answer = resolve }) as unknown as Response,
    }))
    const user = userEvent.setup()
    renderAt()
    fireEvent.change(await screen.findByLabelText('Event name'), { target: { value: 'Town Hall 2027' } })
    await toReview(user)
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'Rename' } })
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByRole('button', { name: 'Sending…' })).toBeDisabled()
    answer(jsonResponse(409, { message: 'This event has already finished.' }))
    expect(await screen.findByText('This event has already finished.')).toBeInTheDocument()
    expect(screen.getByLabelText('Why is this change needed?')).toHaveValue('Rename')
    expect(screen.getByRole('button', { name: 'Send change request' })).toBeEnabled()
  })

  it('EO03 (failure): an unreadable answer to sending shows a general "could not be sent" message', async () => {
    seedSession('organiser')
    stubApi(routes({ 'POST /api/change-requests': unreadable }))
    const user = userEvent.setup()
    renderAt()
    fireEvent.change(await screen.findByLabelText('Event name'), { target: { value: 'Town Hall 2027' } })
    await toReview(user)
    fireEvent.change(screen.getByLabelText('Why is this change needed?'), { target: { value: 'Rename' } })
    await user.click(screen.getByRole('button', { name: 'Send change request' }))

    expect(await screen.findByText('Your change request could not be sent. Please try again.')).toBeInTheDocument()
  })
})
