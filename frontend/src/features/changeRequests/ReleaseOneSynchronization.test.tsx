// @stories EO03 EC-NEW3 TS01 SO-C1 AT02
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SessionProvider } from '../../lib/session'
import { useRefreshingResource } from '../../lib/useRefreshingResource'
import { jsonResponse, seedSession, stubApi } from '../../test/apiStubs'
import { ChangeRequestPanel } from './ChangeRequestPanel'
import { ChangeRequestFormPage } from './ChangeRequestFormPage'
import { EquipmentRequestsPage } from '../technicalSupport/requests/EquipmentRequestsPage'
import { SafetyReviewsPage } from '../safetyOfficer/SafetyReviewsPage'
import { AttendeeEventDetailsPage } from '../attendee/AttendeeEventDetailsPage'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const flush = () => act(async () => { await Promise.resolve() })
function page(element: React.ReactNode) {
  return render(<MemoryRouter><SessionProvider>{element}</SessionProvider></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); window.localStorage.clear() })

describe('EC-NEW3 TS01 SO-C1 synchronization regressions', () => {
  it('AT02 (happy): ATT refreshes approved attendance 200 to 150, capacity and remaining places with distinct labels', async () => {
    vi.useFakeTimers()
    let event = { eventId: 'e1', eventName: 'Approved workshop', description: null,
      startDatetime: '2030-03-10T01:00:00Z', endDatetime: '2030-03-10T04:00:00Z', status: 'confirmed', updatedAt: null,
      expectedAttendance: 200, registrationRequired: true, registrationCapacity: 200, availablePlaces: 198,
      registrationOpensAt: '2020-01-01T01:00:00Z', registrationClosesAt: '2030-03-09T01:00:00Z',
      accessibilityInformation: [], venues: [], registrationReference: null, registeredAt: null }
    stubApi({ 'GET /api/attendee/events/e1': () => jsonResponse(200, event) })
    render(<MemoryRouter initialEntries={['/attendee/events/e1']}><Routes>
      <Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} />
    </Routes></MemoryRouter>)
    await flush()
    expect(screen.getByText('Expected attendance').nextElementSibling).toHaveTextContent('200')
    expect(screen.getByText('Registration capacity').nextElementSibling).toHaveTextContent('200')
    event = { ...event, expectedAttendance: 150, registrationCapacity: 150, availablePlaces: 148 }
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(screen.getByText('Expected attendance').nextElementSibling).toHaveTextContent('150')
    expect(screen.getByText('Registration capacity').nextElementSibling).toHaveTextContent('150')
    expect(screen.getByText('148 places available')).toBeInTheDocument()
  })
  it('EC-NEW3 (conflict): a slow older read cannot overwrite a newer server result', async () => {
    vi.useFakeTimers()
    const old = deferred<string>(), latest = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const hook = renderHook(() => useRefreshingResource<string>(load, 'event'))
    await act(async () => { vi.advanceTimersByTime(5000) })
    await act(async () => { latest.resolve('approved 150') })
    await act(async () => { old.resolve('old 200') })
    expect(hook.result.current.data).toBe('approved 150')
  })

  it('EC-NEW3 (boundary): polling does not starve a valid slow read and mutation invalidates older reads', async () => {
    vi.useFakeTimers()
    const first = deferred<string>(), second = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const hook = renderHook(() => useRefreshingResource<string>(load, 'event'))
    await act(async () => { vi.advanceTimersByTime(5000) })
    await act(async () => { first.resolve('saved server value') })
    expect(hook.result.current.data).toBe('saved server value')
    act(() => hook.result.current.setData('confirmed mutation'))
    await act(async () => { second.resolve('pre-mutation result') })
    expect(hook.result.current.data).toBe('confirmed mutation')
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('EC-NEW3 (happy): EC discovers a pending EO change and removes its indicator when resolved', async () => {
    vi.useFakeTimers(); seedSession('coordinator')
    let changes: unknown[] = []
    stubApi({ 'GET /api/change-requests?eventId=e1': () => jsonResponse(200, changes) })
    page(<ChangeRequestPanel eventId="e1" journeyCode="PLANNING" />)
    await flush()
    expect(screen.queryByRole('region', { name: 'Change requested' })).not.toBeInTheDocument()
    changes = [{ changeRequestId: 'c1', requestId: 'r1', status: 'pending', closedReason: null,
      requestedByName: 'eo1', changes: [{ label: 'Equipment items and quantities' }], canDecide: true }]
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    expect(screen.getByRole('region', { name: 'Change requested' })).toHaveTextContent('Equipment items and quantities')
    changes = [{ ...changes[0] as object, status: 'rejected' }]
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByRole('region', { name: 'Change requested' })).not.toBeInTheDocument()
  })

  it('TS01 (happy): TS discovers requests automatically and distinguishes types from units', async () => {
    vi.useFakeTimers(); seedSession('technical-support')
    let requests: unknown[] = []
    const calls = stubApi({ 'GET /api/equipment-requests?status=all': () => jsonResponse(200, requests) })
    page(<EquipmentRequestsPage />); await flush()
    expect(screen.getByText('No equipment requests are awaiting review.')).toBeInTheDocument()
    requests = [{ requestId: 'q1', eventId: 'e1', eventName: 'Updated workshop',
      eventStart: '2027-03-10T01:00:00Z', eventEnd: '2027-03-10T04:00:00Z', status: 'processing',
      items: [{ equipmentId: 'p1', equipmentName: 'Projector', quantity: 3 }, { equipmentId: 'm1', equipmentName: 'Microphone', quantity: 5 }] }]
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(calls.map(call => call.url)).toEqual(['/api/equipment-requests?status=all', '/api/equipment-requests?status=all'])
    expect(screen.getByText('Updated workshop')).toBeInTheDocument()
    expect(screen.getByText('3 × Projector, 5 × Microphone')).toBeInTheDocument()
    expect(screen.getByText('Equipment types').nextElementSibling).toHaveTextContent('2')
    expect(screen.getByText('Total units requested').nextElementSibling).toHaveTextContent('8')
  })

  it('SO-C1 (happy): SO discovers a newly submitted safety review without navigation', async () => {
    vi.useFakeTimers(); seedSession('safety-officer')
    let planning: unknown[] = []
    stubApi({ 'GET /api/safety/events/planning': () => jsonResponse(200, planning),
      'GET /api/safety/events/confirmed': () => jsonResponse(200, []) })
    page(<SafetyReviewsPage />); await flush()
    planning = [{ eventId: 'e1', eventName: 'New safety review', status: 'awaiting_safety_review',
      startDatetime: '2027-03-10T01:00:00Z', endDatetime: '2027-03-10T04:00:00Z', expectedAttendance: 150 }]
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(screen.getByRole('link', { name: /New safety review/ })).toHaveAttribute('href', '/safety-officer/events/e1')
  })
})

describe('EO03 capacity and equipment proposals', () => {
  it('EO03 (boundary): attendance 200 to 150 requires explicit capacity confirmation and catalogue quantities', async () => {
    seedSession('organiser')
    stubApi({
      'GET /api/event-requests/r1': () => jsonResponse(200, { requestId: 'r1', eventId: 'e1', eventName: 'Workshop', purpose: 'Learning',
        startDatetime: '2030-03-10T01:00:00Z', endDatetime: '2030-03-10T04:00:00Z', expectedAttendance: 200,
        venueRequirements: 'Room', accessibilityNeeds: ['none'], requiredFacilities: [], registrationNeeds: true,
        registrationCapacity: 200, registrationOpensAt: '2030-01-01T01:00:00Z', registrationClosesAt: '2030-03-09T01:00:00Z' }),
      'GET /api/change-requests?requestId=r1': () => jsonResponse(200, []),
      'GET /api/events/e1/registrations': () => jsonResponse(200, { confirmedCount: 10 }),
      'GET /api/equipment/catalogue': () => jsonResponse(200, [{ equipmentId: 'p1', equipmentName: 'Projector' }]),
    })
    render(<MemoryRouter initialEntries={['/organiser/requests/r1/change']}><SessionProvider><Routes>
      <Route path="/organiser/requests/:requestId/change" element={<ChangeRequestFormPage />} />
    </Routes></SessionProvider></MemoryRouter>)
    const attendance = await screen.findByLabelText('Expected attendance')
    fireEvent.change(attendance, { target: { value: '150' } })
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
    fireEvent.click(screen.getByLabelText('I confirm this registration capacity for the changed expected attendance'))
    fireEvent.click(screen.getByText('Add or remove equipment'))
    fireEvent.click(screen.getByRole('button', { name: 'Increase Projector quantity' }))
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Projector quantity' }), { target: { value: '0' } })
    expect(screen.getByRole('spinbutton', { name: 'Projector quantity' })).toHaveValue(0)
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeEnabled()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Projector quantity' }), { target: { value: '-1' } })
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Projector quantity' }), { target: { value: '3' } })
    expect(screen.getByRole('button', { name: 'Review the change' })).toBeEnabled()
    expect(screen.getByLabelText('Registration capacity')).toHaveValue(200)
    expect(screen.getByLabelText('Registration closes')).toHaveValue('2030-03-09T09:00')
    fireEvent.change(screen.getByLabelText('Registration capacity'), { target: { value: '9' } })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Review the change' })).toBeDisabled()
      expect(screen.getByText(/Capacity cannot be lower than the 10 active confirmed/)).toBeInTheDocument()
    })
  })
})
