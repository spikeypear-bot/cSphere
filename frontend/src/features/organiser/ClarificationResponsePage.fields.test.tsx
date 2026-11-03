// @stories EO26 EO-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ClarificationResponsePage } from './ClarificationResponsePage'

const ID = 'req-1'
const unreadable = () => ({ ok: true, status: 200, text: async () => '<html>' }) as Response

const request = {
  requestId: ID, requestType: 'C', eventId: null, eventName: 'Town Hall', purpose: 'Quarterly update',
  description: null, startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z',
  expectedAttendance: 150, venueRequirements: 'Theatre seating', equipmentRequirements: null,
  accessibilityNeeds: ['none'], registrationNeeds: false, status: 'clarification_required',
  createdAt: '2026-09-20T01:00:00Z', updatedAt: '2026-09-21T01:00:00Z', organisation: 'Acme Pte Ltd',
}

const question = {
  activityId: 'a2', type: 'clarification_requested', actorName: 'ec1', actorRole: 'ec',
  message: 'Which rooms do you need?', flaggedFields: ['venueRequirements'],
  fromStatus: 'pending', toStatus: 'clarification_required', occurredAt: '2026-09-21T01:00:00Z',
}

function routes(extra: Record<string, (body: unknown) => Response> = {}, found: object = request, timeline: object[] = [question]) {
  return {
    [`GET /api/event-requests/${ID}`]: () => jsonResponse(200, found),
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
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('EO26 — answering a clarification, every field and failure', () => {
  beforeEach(() => seedSession('organiser'))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('EO26 (failure): when the request cannot be loaded the organiser sees why and the way back', async () => {
    stubApi(routes({ [`GET /api/event-requests/${ID}`]: () => jsonResponse(500, { message: 'Requests are unavailable.' }) }))
    const first = renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Requests are unavailable.')
    expect(screen.getByRole('link', { name: 'Back to your requests' })).toHaveAttribute('href', '/organiser/requests')
    first.unmount()

    stubApi(routes({ [`GET /api/event-requests/${ID}/timeline`]: unreadable }))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load this request.')
  })

  it('EO26 (happy): text, accessibility, facilities and registration can all be changed, and Save sends them', async () => {
    const calls = stubApi(routes({ [`PUT /api/event-requests/${ID}`]: () => jsonResponse(200, request) }))
    renderPage()

    const venue = await screen.findByLabelText('Venue requirements')
    fireEvent.change(venue, { target: { value: 'Two rooms' } })
    fireEvent.click(screen.getByRole('button', { name: 'Wheelchair support' }))
    expect(screen.getByRole('button', { name: 'No accessibility requirements needed' })).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'No accessibility requirements needed' }))
    expect(screen.getByRole('button', { name: 'Wheelchair support' })).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Wheelchair support' }))
    fireEvent.click(screen.getByLabelText('Stage'))
    fireEvent.click(screen.getByLabelText('Projection'))
    fireEvent.click(screen.getByLabelText('Stage'))

    expect(screen.queryByLabelText('Registration capacity')).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Attendees need to register'))
    fireEvent.change(screen.getByLabelText('Registration capacity'), { target: { value: '120' } })
    fireEvent.change(screen.getByLabelText('Registration opens'), { target: { value: '2027-03-01T09:00' } })
    fireEvent.change(screen.getByLabelText('Attendee instructions'), { target: { value: 'Bring ID' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Your request is still with you')
    expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({
      venueRequirements: 'Two rooms', accessibilityNeeds: ['wheelchair_support'], requiredFacilities: ['projection'],
      registrationNeeds: true, registrationCapacity: 120, registrationOpensAt: '2027-03-01T01:00:00.000Z',
      attendeeInstructions: 'Bring ID',
    })
    // Editing again means it is no longer "saved".
    fireEvent.change(screen.getByLabelText('Attendee instructions'), { target: { value: 'Bring ID.' } })
    expect(screen.queryByText(/Your request is still with you/)).not.toBeInTheDocument()
  })

  it('EO26 (boundary): with no question on record and no event name, the form still works; a reply over 2,000 characters is refused', async () => {
    stubApi(routes({}, { ...request, eventName: '', requiredFacilities: undefined }, []))
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Your event request' })).toBeInTheDocument()
    expect(document.querySelector('.clarify__question')).toBeNull()
    expect(screen.queryByText('Requested by your coordinator')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/Your reply to the coordinator/), { target: { value: 'x'.repeat(2001) } })
    expect(screen.getByText('2001/2000')).toHaveClass('field-error')
    expect(screen.getByRole('button', { name: 'Save and resubmit' })).toBeDisabled()
  })

  it('EO26 (boundary): a flagged field the page has no label for is still named', async () => {
    stubApi(routes({}, request, [{ ...question, flaggedFields: ['venueRequirements', 'cateringNotes'] }]))
    renderPage()
    expect(await screen.findByText('About: Venue requirements, cateringNotes')).toBeInTheDocument()
  })

  it('EO26 (failure): unreadable answers to saving or resubmitting are reported plainly and nothing claims success', async () => {
    stubApi(routes({
      [`PUT /api/event-requests/${ID}`]: unreadable,
      [`POST /api/event-requests/${ID}/resubmit`]: unreadable,
    }))
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Your changes could not be saved.')
    expect(screen.queryByText(/Your request is still with you/)).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Your reply to the coordinator/), { target: { value: 'Two rooms' } })
    await user.click(screen.getByRole('button', { name: 'Save and resubmit' }))
    expect(await screen.findByText('The request could not be resubmitted.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save and resubmit' })).toBeEnabled()
  })
})
