// @stories AT04 AT01 AT02
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MyRegistrationsPage } from './MyRegistrationsPage'
import { RegistrationDetailsPage } from './RegistrationDetailsPage'
import { AttendeeEventDetailsPage } from './AttendeeEventDetailsPage'
import type { RegistrationDetails } from './registrationView'

const registration: RegistrationDetails = {
  registrationId: 'reg-1', reference: 'CS-REFERENCE', registeredAt: '2026-12-01T00:00:00Z',
  registrationStatus: 'confirmed', eventId: 'event-1', eventName: 'Community Conference',
  startDatetime: '2027-03-01T09:00:00+08:00', endDatetime: '2027-03-01T17:00:00+08:00',
  eventStatus: 'confirmed', eventDetailsAvailable: true, withdrawalAvailable: true,
  withdrawalReason: 'Withdrawal is available before the event starts.',
}
const eventDetails = {
  eventId: 'event-1', eventName: 'Community Conference', startDatetime: registration.startDatetime,
  endDatetime: registration.endDatetime, status: 'confirmed', updatedAt: null,
  accessibilityInformation: [], venues: [], registrationRequired: true, registrationCapacity: 10,
  availablePlaces: 5, registrationOpensAt: null, registrationClosesAt: null,
  registrationReference: null, registeredAt: null,
}
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) })

function renderPage(path = '/attendee/my-registrations') {
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/attendee/my-registrations" element={<MyRegistrationsPage />} />
    <Route path="/attendee/my-registrations/:registrationId" element={<RegistrationDetailsPage />} />
    <Route path="/attendee/events/:eventId" element={<AttendeeEventDetailsPage />} />
    <Route path="/attendee/browse" element={<h1>Browse events</h1>} />
  </Routes></MemoryRouter>)
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('AT04 registration pages', () => {
  it('AT04 (happy): shows requested fields with registration and event statuses separately, then opens details', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response([registration])).mockResolvedValue(response(registration))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    expect(await screen.findByText('CS-REFERENCE')).toBeInTheDocument()
    expect(screen.getByText('Registration status')).toBeInTheDocument()
    expect(screen.getByText('Event status')).toBeInTheDocument()
    expect(screen.getByText('1 March 2027 at 9:00 am (SGT)')).toBeInTheDocument()
    expect(screen.getByText('1 March 2027 at 5:00 pm (SGT)')).toBeInTheDocument()
    expect(screen.getByText('1 December 2026 at 8:00 am (SGT)')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'View registration' }))
    expect(await screen.findByText(/Available\. Withdrawal is available/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View latest event details' })).toHaveAttribute('href', '/attendee/events/event-1')
    expect(fetchMock).toHaveBeenLastCalledWith('/api/attendee/registrations/reg-1', expect.objectContaining({ method: 'GET', cache: 'no-store' }))
    expect(screen.queryByRole('button', { name: /withdraw/i })).not.toBeInTheDocument()
  })

  it('AT04 (boundary): loading is visible until the list resolves', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('Loading registrations')
  })

  it('AT04 (boundary): empty history offers navigation to events', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])))
    renderPage()
    expect(await screen.findByRole('heading', { name: 'No registrations yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Browse events' })).toHaveAttribute('href', '/attendee/browse')
  })

  it.each(['/attendee/my-registrations', '/attendee/my-registrations/reg-1'])(
    'AT04 (failure): API failure and retry recover %s', async path => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('offline'))
        .mockResolvedValue(response(path.endsWith('reg-1') ? registration : [registration])))
      renderPage(path)
      fireEvent.click(await screen.findByRole('button', { name: 'Try again' }))
      expect(await screen.findByText('CS-REFERENCE')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    },
  )

  it('AT04 (negative): unavailable ownership response reveals no registration details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: 'Registration unavailable.' }, 404)))
    renderPage('/attendee/my-registrations/other')
    expect(await screen.findByRole('alert')).toHaveTextContent(/unavailable or does not belong/)
    expect(screen.queryByText('CS-REFERENCE')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'View latest event details' })).not.toBeInTheDocument()
  })

  it.each([
    ['cancelled', 'confirmed', 'This event has been cancelled.', 'Confirmed', 'Cancelled'],
    ['completed', 'confirmed', 'This event has completed.', 'Confirmed', 'Completed'],
    ['reconfirming', 'confirmed', 'Existing registrations have not been automatically withdrawn.', 'Confirmed', 'Awaiting re-confirmation'],
    ['cancelled', 'withdrawn', 'This event has been cancelled.', 'Withdrawn', 'Cancelled'],
    ['cancelled', 'event_cancelled', 'This event has been cancelled.', 'Cancelled by event cancellation', 'Cancelled'],
  ] as const)('AT04 (cross-cutting): %s event preserves %s registration history', async (eventStatus, registrationStatus, message, registrationLabel, eventLabel) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...registration, eventStatus, registrationStatus,
      withdrawalAvailable: false, withdrawalReason: 'Withdrawal is unavailable.' })))
    renderPage('/attendee/my-registrations/reg-1')
    expect(await screen.findByText(new RegExp(message.replaceAll('.', '\\.')))).toBeInTheDocument()
    const registrationFact = screen.getByText('Registration status').parentElement!
    const eventFact = screen.getByText('Event status').parentElement!
    expect(within(registrationFact).getByText(registrationLabel)).toBeInTheDocument()
    expect(within(eventFact).getByText(eventLabel)).toBeInTheDocument()
    expect(screen.getByText(/Unavailable\. Withdrawal is unavailable/)).toBeInTheDocument()
  })

  it('AT04 (happy): withdrawn records remain in the list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([{ ...registration, registrationStatus: 'withdrawn' }])))
    renderPage()
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
    expect(screen.getByText('CS-REFERENCE')).toBeInTheDocument()
  })

  it('AT04 (happy): missing attendee-facing event details have a clear message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...registration, eventDetailsAvailable: false })))
    renderPage('/attendee/my-registrations/reg-1')
    expect(await screen.findByText(/event details are currently unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'View latest event details' })).not.toBeInTheDocument()
  })

  it.each(['list', 'details'])('AT04 (cross-cutting): %s refreshes a withdrawn status on focus', async page => {
    const payload = (value: RegistrationDetails) => page === 'list' ? [value] : value
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(payload(registration)))
      .mockResolvedValue(response(payload({ ...registration, registrationStatus: 'withdrawn', withdrawalAvailable: false }))))
    renderPage(page === 'list' ? undefined : '/attendee/my-registrations/reg-1')
    expect(await screen.findByText('CS-REFERENCE')).toBeInTheDocument()
    fireEvent(window, new Event('focus'))
    expect(await screen.findByText('Withdrawn')).toBeInTheDocument()
  })

  it('AT04 AT01 AT02 (cross-cutting): registering, opening history, and returning to latest event details use current data', async () => {
    let registered = false
    const fetchMock = vi.fn(async (url: string, options: RequestInit) => {
      if (options.method === 'POST') {
        registered = true
        return response({ reference: registration.reference, registeredAt: registration.registeredAt })
      }
      if (url === '/api/attendee/registrations') return response(registered ? [registration] : [])
      if (url === '/api/attendee/registrations/reg-1') return response(registration)
      return response({ ...eventDetails, registrationReference: registered ? registration.reference : null,
        registeredAt: registered ? registration.registeredAt : null })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage('/attendee/events/event-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Register for this event' }))
    expect(await screen.findByText('CS-REFERENCE')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'My Registrations' }))
    expect(await screen.findByRole('link', { name: 'View registration' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'View registration' }))
    fireEvent.click(await screen.findByRole('link', { name: 'View latest event details' }))
    expect(await screen.findByText(/You’re registered/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(1)
  })
})
