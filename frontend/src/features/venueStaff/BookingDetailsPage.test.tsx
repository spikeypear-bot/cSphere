// @stories VS02 VS03 VS04 VS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { VenueCataloguePage } from './VenueCataloguePage'
import { VenueDetailsPage } from './VenueDetailsPage'
import { BookingDetailsPage } from './BookingDetailsPage'
import type { VenueBookingDto } from '../../types/venueBooking'

const booking: VenueBookingDto = { bookingId: 'b1', status: 'pending',
  venue: { venueId: 'v1', venueAddress: 'Seminar Room', venueCapacity: 200,
    supportedLayouts: ['classroom'], venueAccessibilities: ['step_free_access'], venueFacilities: ['projection'],
    operatingInformation: 'Daily', additionalInformation: null },
  event: { eventId: 'e1', eventName: 'Workshop', startDatetime: '2026-09-24T15:00:00Z',
    endDatetime: '2026-09-24T18:00:00Z', expectedAttendance: 120, venueRequirements: 'Classroom seating',
    accessibilityNeeds: [], equipmentRequirements: null },
}
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
function page(path = '/venue-staff/catalogue') {
  render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/venue-staff/catalogue" element={<VenueCataloguePage />} />
    <Route path="/venue-staff/catalogue/:venueId" element={<VenueDetailsPage />} />
    <Route path="/venue-staff/bookings/:bookingId" element={<BookingDetailsPage />} />
  </Routes></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it.each(['approved', 'pending', 'rejected', 'changed', 'cancelled'] as const)('exposes the %s booking status for colour styling on details and venue lists', async status => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/bookings')) return response([{ ...booking, status }])
    if (url.endsWith('/occupancy-settings')) return response({ setupMinutes: 0, turnaroundMinutes: 0 })
    if (url.endsWith('/unavailability') || url.endsWith('/operational-issues')) return response([])
    if (url.endsWith('/venues/v1')) return response(booking.venue)
    return response({ ...booking, status })
  }))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  expect(await screen.findByLabelText('Status: ' + status)).toHaveAttribute('data-booking-status', status)
  cleanup()
  page('/venue-staff/catalogue/v1')
  const list = await screen.findByRole('region', { name: 'Associated bookings' })
  const label = await within(list).findByText(status, { selector: 'span' })
  expect(label).toHaveAttribute('data-booking-status', status)
})

it.each([undefined, null])('defaults an absent booking status (%s) to pending and follows an approval response', async status => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) =>
    response({ ...booking, status: init.method === 'PATCH' ? 'approved' : status })))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  expect(await screen.findByLabelText('Status: pending')).toHaveTextContent('pending')
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByLabelText('Status: approved')).toHaveTextContent('approved')
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
})

it('rejects with an alternative venue and arrangement and displays both suggestions', async () => {
  const alternativeVenue = { ...booking.venue, venueId: 'v2', venueAddress: 'Garden Hall' }
  const rejected = { ...booking, status: 'rejected' as const, rejectReason: 'Unavailable on that date',
    alternativeVenueId: alternativeVenue.venueId, alternativeVenueAddress: alternativeVenue.venueAddress,
    alternativeArrangement: 'Move to the afternoon' }
  const fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/venues')) return response([booking.venue, alternativeVenue])
    if (url.endsWith('/venue-bookings/b1/reject') && init?.method === 'PATCH') return response(rejected)
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()

  await user.click(await screen.findByRole('button', { name: 'Reject Booking' }))
  await user.selectOptions(await screen.findByLabelText('Alternative venue (optional)'), 'v2')
  await user.type(screen.getByLabelText('Rejection reason'), 'Unavailable on that date')
  await user.type(screen.getByLabelText('Alternative arrangement (optional)'), 'Move to the afternoon')
  await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))

  await screen.findByLabelText('Status: rejected')
  const requirements = screen.getByRole('region', { name: 'Event Requirements' })
  const rejection = screen.getByRole('status', { name: 'Rejection reason' })
  expect(requirements).not.toContainElement(rejection)
  expect(rejection).toHaveTextContent('Unavailable on that date')
  expect(rejection).toHaveTextContent(/Suggested Alternative Venue:\s*Garden Hall/)
  expect(rejection).toHaveTextContent('Move to the afternoon')
  const patch = fetch.mock.calls.find(call => call[1]?.method === 'PATCH')
  expect(JSON.parse(String(patch?.[1].body))).toEqual({
    reason: 'Unavailable on that date',
    alternativeVenueId: 'v2',
    alternativeArrangement: 'Move to the afternoon',
  })
})

it('allows rejection without an alternative when the venue list cannot be loaded', async () => {
  const rejected = { ...booking, status: 'rejected' as const, rejectReason: 'Unavailable' }
  const fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/venues')) return response({ message: 'Venue catalogue unavailable' }, 503)
    if (url.endsWith('/venue-bookings/b1/reject') && init?.method === 'PATCH') return response(rejected)
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()

  await user.click(await screen.findByRole('button', { name: 'Reject Booking' }))
  expect(await screen.findByText('Venue catalogue unavailable')).toBeInTheDocument()
  await user.type(screen.getByLabelText('Rejection reason'), 'Unavailable')
  await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))

  await screen.findByLabelText('Status: rejected')
  expect(screen.getByRole('status', { name: 'Rejection reason' })).toHaveTextContent('Unavailable')
  const patch = fetch.mock.calls.find(call => call[1]?.method === 'PATCH')
  expect(JSON.parse(String(patch?.[1].body))).toEqual({
    reason: 'Unavailable',
    alternativeVenueId: null,
    alternativeArrangement: null,
  })
})

it('navigates catalogue to venue to booking, displays requirements and refetches on reopening', async () => {
  let attendance = 120
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/venues')) return response([booking.venue])
    if (url.endsWith('/venues/v1')) return response(booking.venue)
    if (url.endsWith('/venues/v1/bookings')) return response([booking])
    if (url.endsWith('/venue-bookings/b1')) return response({ ...booking, event: { ...booking.event, expectedAttendance: attendance } })
    throw new Error('Unexpected URL: ' + url)
  })
  vi.stubGlobal('fetch', fetch); page()
  const user = userEvent.setup()
  await user.click(await screen.findByRole('link', { name: 'View venue details' }))
  expect(await screen.findByRole('heading', { name: 'Associated bookings' })).toBeInTheDocument()
  await user.click(await screen.findByRole('link', { name: 'View booking details' }))
  const requirements = await screen.findByRole('region', { name: 'Event Requirements' })
  expect(within(requirements).getByText('120 people')).toBeInTheDocument()
  expect(within(requirements).getByText('Classroom seating')).toBeInTheDocument()
  expect(within(requirements).getAllByText('Not specified')).toHaveLength(2)
  expect(within(requirements).getByText(/24 Sept? 2026/)).toBeInTheDocument()
  expect(within(requirements).getByText(/25 Sept? 2026/)).toBeInTheDocument()
  expect(within(requirements).queryByRole('textbox')).not.toBeInTheDocument()
  expect(within(requirements).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Venue Information' })).toHaveTextContent('200 people')
  attendance = 150
  await user.click(screen.getByRole('link', { name: 'Back to venue details' }))
  await user.click(await screen.findByRole('link', { name: 'View booking details' }))
  expect(await screen.findByText('150 people')).toBeInTheDocument()
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/venue-bookings/b1'))).toHaveLength(2)
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
})

it('displays empty associated bookings', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => response(url.endsWith('/bookings') ? [] : booking.venue)))
  page('/venue-staff/catalogue/v1')
  expect(await screen.findByText('No associated bookings.')).toBeInTheDocument()
})

it('shows a booking error and can retry without showing stale details', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ message: 'Venue booking not found' }, 404))
    .mockResolvedValueOnce(response(booking)).mockResolvedValue(response([booking]))
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Venue booking not found')
  expect(screen.queryByRole('region', { name: 'Event Requirements' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('120 people')).toBeInTheDocument()
})

it('preserves explicit no accessibility requirements and displays existing equipment requirements', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => response(url.endsWith('/bookings') ? [booking] : { ...booking,
    event: { ...booking.event, accessibilityNeeds: ['none'], equipmentRequirements: 'Projector' } })))
  page('/venue-staff/bookings/b1')
  const requirements = await screen.findByRole('region', { name: 'Event Requirements' })
  expect(within(requirements).getByText('No accessibility requirements')).toBeInTheDocument()
  expect(within(requirements).getByText('Projector')).toBeInTheDocument()
})


it('shows missing venue errors without exposing an associated booking list', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: 'Venue not found' }, 404)))
  page('/venue-staff/catalogue/missing')
  expect(await screen.findByRole('alert')).toHaveTextContent('Venue not found')
  expect(screen.queryByRole('heading', { name: 'Associated bookings' })).not.toBeInTheDocument()
})

it('retains venue details when associated bookings fail and supports retry', async () => {
  let attempts = 0
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/bookings')) return ++attempts === 1 ? response({ message: 'Could not load bookings' }, 500) : response([booking])
    return response(booking.venue)
  }))
  page('/venue-staff/catalogue/v1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load bookings')
  expect(screen.getByText('Seminar Room')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Retry bookings' }))
  expect(await screen.findByRole('link', { name: 'View booking details' })).toBeInTheDocument()
})


it('paginates within the venue and fetches current details for each selected booking', async () => {
  const second = { ...booking, bookingId: 'b2', event: { ...booking.event, eventName: 'Seminar', expectedAttendance: 180 } }
  let attendance = 120
  const fetch = vi.fn().mockImplementation(async (url: string) => response(
    url.endsWith('/bookings') ? [booking, second] : url.endsWith('/b2') ? second
      : { ...booking, event: { ...booking.event, expectedAttendance: attendance } }))
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  const user = userEvent.setup()
  expect(await screen.findByText('Booking 1 of 2')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Previous' })).toHaveAttribute('aria-disabled', 'true')
  await user.click(screen.getByRole('button', { name: 'Next' }))
  expect(await screen.findByText('180 people')).toBeInTheDocument()
  expect(await screen.findByText('Booking 2 of 2')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toHaveAttribute('aria-disabled', 'true')
  attendance = 150
  await user.click(screen.getByRole('button', { name: 'Previous' }))
  expect(await screen.findByText('150 people')).toBeInTheDocument()
  expect(await screen.findByText('Booking 1 of 2')).toBeInTheDocument()
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
})


it('keeps both panels and navigation mounted while paging without refetching the list', async () => {
  const second = { ...booking, bookingId: 'b2', event: { ...booking.event, eventName: 'Second event' } }
  let finish!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { finish = resolve })
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/bookings')) return response([booking, second])
    if (url.endsWith('/b2')) return pending
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  await screen.findByText('Booking 1 of 2')
  const venuePanel = screen.getByRole('region', { name: 'Venue Information' })
  const requirementsPanel = screen.getByRole('region', { name: 'Event Requirements' })
  const navigation = screen.getByRole('navigation', { name: 'Venue bookings' })
  const next = screen.getByRole('button', { name: 'Next' })
  next.focus()
  const user = userEvent.setup()
  await user.keyboard('{Enter}')
  expect(next).toHaveFocus()
  await user.keyboard('{Enter}')
  expect(screen.getByRole('region', { name: 'Venue Information' })).toBe(venuePanel)
  expect(screen.getByRole('region', { name: 'Event Requirements' })).toBe(requirementsPanel)
  expect(screen.getByRole('navigation', { name: 'Venue bookings' })).toBe(navigation)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toHaveAttribute('aria-disabled', 'true')
  await act(async () => { finish(response(second)) })
  expect(await screen.findByText('Second event')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toBe(next)
  expect(next).toHaveFocus()
  await user.keyboard(' ')
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/b2'))).toHaveLength(1)
  expect(screen.getByRole('region', { name: 'Venue Information' })).toBe(venuePanel)
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/venues/v1/bookings'))).toHaveLength(1)
})


it('retains the displayed booking and navigation after failure and throughout retry', async () => {
  const second = { ...booking, bookingId: 'b2', event: { ...booking.event, eventName: 'Recovered event' } }
  let attempts = 0
  let finish!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { finish = resolve })
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/bookings')) return response([booking, second])
    if (url.endsWith('/b2')) return ++attempts === 1 ? response({ message: 'Unavailable' }, 500) : pending
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  await screen.findByText('Booking 1 of 2')
  const venue = screen.getByRole('region', { name: 'Venue Information' })
  const requirements = screen.getByRole('region', { name: 'Event Requirements' })
  const navigation = screen.getByRole('navigation', { name: 'Venue bookings' })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Next' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Showing the previously loaded booking')
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Event Requirements' })).toBe(requirements)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Venue Information' })).toBe(venue)
  expect(screen.getByRole('region', { name: 'Event Requirements' })).toBe(requirements)
  expect(screen.getByRole('navigation', { name: 'Venue bookings' })).toBe(navigation)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  await act(async () => { finish(response(second)) })
  expect(await screen.findByText('Recovered event')).toBeInTheDocument()
  expect(screen.getByText('Booking 2 of 2')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Event Requirements' })).toBe(requirements)
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/venues/v1/bookings'))).toHaveLength(1)
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
})

it('reviews the booking, cancels without writing, then approves exactly once and displays the response', async () => {
  let finish!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { finish = resolve })
  const fetch = vi.fn().mockImplementation(async (url: string, init: RequestInit) => {
    if (init.method === 'PATCH') return pending
    if (url.endsWith('/bookings')) return response([booking])
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  const approval = screen.getByRole('region', { name: 'Booking approval' })
  expect(approval).toHaveTextContent('Workshop')
  expect(approval).toHaveTextContent('Seminar Room')
  expect(approval).toHaveTextContent('Singapore time')
  await user.click(within(approval).getByRole('button', { name: 'Cancel' }))
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
  await user.click(screen.getByRole('button', { name: 'Approve Booking' }))
  await user.dblClick(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(screen.getByRole('button', { name: 'Approving…' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  expect(fetch.mock.calls.filter(call => call[1].method === 'PATCH')).toHaveLength(1)
  expect(fetch.mock.calls.find(call => call[1].method === 'PATCH')?.[0]).toBe('/api/venue-bookings/b1/approve')
  await act(async () => { finish(response({ ...booking, status: 'approved' })) })
  expect(await screen.findByLabelText('Status: approved')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
})

it.each(['approved', 'changed', 'rejected', 'cancelled'] as const)('does not offer approval for %s', async status => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...booking, status })))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  await screen.findByLabelText('Status: ' + status)
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
})

it.each([404, 409, 503, 0])('keeps the booking pending and shows an error for failure %s', async code => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') {
      if (!code) throw new TypeError('Network failure')
      return response({ message: 'Approval unavailable' }, code)
    }
    return response(booking)
  }))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.getByLabelText('Status: pending')).toBeInTheDocument()
  expect(screen.queryByLabelText('Status: approved')).not.toBeInTheDocument()
  expect(await screen.findByRole('button', { name: 'Approve Booking' })).toBeEnabled()
})

it.each([false, true])('shows conflict details through automatic refresh (refresh fails: %s)', async failRefresh => {
  let attempted = false
  const fetch = vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') {
      attempted = true
      return response({ message: 'This booking overlaps an existing approved booking.', conflicts: [
        { bookingId: 'b2', eventName: 'Evening workshop', startDatetime: '2026-09-24T15:00:00Z', endDatetime: '2026-09-24T18:00:00Z' },
        { bookingId: 'b3', eventName: 'Second workshop', startDatetime: '2026-09-24T16:00:00Z', endDatetime: '2026-09-24T17:00:00Z' },
      ] }, 409)
    }
    if (attempted && failRefresh) throw new TypeError('Offline')
    return response(booking)
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(failRefresh ? 'Could not refresh booking details' : 'Latest booking status: pending')
  expect(alert).toHaveTextContent('Evening workshop')
  expect(alert).toHaveTextContent('Booking ID: b2')
  expect(alert).toHaveTextContent('Second workshop')
  expect(alert).toHaveTextContent('Booking ID: b3')
  expect(alert).toHaveTextContent('Singapore time, UTC+08:00')
  expect(alert).toHaveTextContent('24 Sept 2026, 11:00 pm')
  expect(alert).toHaveTextContent('25 Sept 2026, 02:00 am')
  expect(screen.getByLabelText('Status: pending')).toBeInTheDocument()
  expect(screen.queryByLabelText('Status: approved')).not.toBeInTheDocument()
  expect(fetch).toHaveBeenLastCalledWith('/api/venue-bookings/b1', expect.objectContaining({ method: 'GET', cache: 'no-store' }))
  if (failRefresh) expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
  else {
    expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Refresh booking details' }))
    expect(screen.queryByText('Evening workshop')).not.toBeInTheDocument()
  }
})

it('shows a buffer-only restriction without falsely highlighting the event times', async () => {
  const message = 'This booking conflicts with the setup or turnaround time required around an existing approved booking.'
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) =>
    init.method === 'PATCH' ? response({ message, conflicts: [{ bookingId: 'buffer-booking', eventName: 'Earlier event',
      startDatetime: '2026-09-24T10:00:00Z', endDatetime: '2026-09-24T14:00:00Z',
      setupDurationMinutes: 30, turnaroundDurationMinutes: 90,
      effectiveStart: '2026-09-24T09:30:00Z', effectiveEnd: '2026-09-24T15:30:00Z' }] }, 409) : response(booking)))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(message)
  const card = within(alert).getByRole('group', { name: 'Conflicting booking: Earlier event' })
  expect(card).toHaveTextContent('Setup Period')
  expect(card).toHaveTextContent('Turnaround Period')
  expect(card).toHaveTextContent('30 minutes')
  expect(card).toHaveTextContent('90 minutes')
  expect(card).toHaveTextContent('Venue Occupied')
  expect(card).toHaveTextContent('24 Sept 2026, 05:30 pm')
  expect(card).toHaveTextContent('24 Sept 2026, 11:30 pm')
  expect(card.querySelector('.booking-time-conflict')).toBeNull()
  expect(screen.getByLabelText('Status: pending')).toBeInTheDocument()
})

it.each([false, true])('highlights the paired boundaries in the conflict card and current request (reverse: %s)', async reverse => {
  const current = { ...booking, event: { ...booking.event,
    startDatetime: reverse ? '2027-10-16T11:30:00+08:00' : '2027-10-16T09:00:00+08:00',
    endDatetime: reverse ? '2027-10-16T14:00:00+08:00' : '2027-10-16T12:00:00+08:00' } }
  const conflict = { bookingId: 'b2', eventName: 'Other event',
    startDatetime: reverse ? '2027-10-16T09:00:00+08:00' : '2027-10-16T11:30:00+08:00',
    endDatetime: reverse ? '2027-10-16T12:00:00+08:00' : '2027-10-16T14:00:00+08:00' }
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) =>
    init.method === 'PATCH' ? response({ message: 'Booking overlap', conflicts: [conflict] }, 409) : response(current)))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  const card = await screen.findByRole('group', { name: 'Conflicting booking: Other event' })
  expect(card.querySelectorAll('strong.booking-time-conflict')).toHaveLength(1)
  expect(card.querySelector('strong.booking-time-conflict')).toHaveTextContent(reverse ? '12:00 pm' : '11:30 am')
  const currentPanel = screen.getByRole('region', { name: 'Event Requirements' })
  expect(currentPanel.querySelectorAll('strong.booking-time-conflict')).toHaveLength(1)
  expect(currentPanel.querySelector('strong.booking-time-conflict')).toHaveTextContent(reverse ? '11:30 am' : '12:00 pm')
  await user.click(screen.getByRole('button', { name: 'Refresh booking details' }))
  expect(screen.queryByRole('group', { name: 'Conflicting booking: Other event' })).not.toBeInTheDocument()
  expect(currentPanel.querySelector('.booking-time-conflict')).toBeNull()
})

it.each([false, true])('shows unavailable periods and highlights both sides (refresh fails: %s)', async failRefresh => {
  let attempted = false
  const current = { ...booking, event: { ...booking.event, startDatetime: '2027-10-16T09:00:00+08:00', endDatetime: '2027-10-16T12:00:00+08:00' } }
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') {
      attempted = true
      return response({ message: 'Recorded unavailability', conflicts: [], unavailablePeriods: [
        { unavailabilityId: 'u1', reason: 'Maintenance', startDatetime: '2027-10-16T11:30:00+08:00', endDatetime: '2027-10-16T15:00:00+08:00' },
      ] }, 409)
    }
    if (attempted && failRefresh) throw new TypeError('Offline')
    return response(current)
  }))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  const card = await screen.findByRole('group', { name: 'Unavailable period: Maintenance' })
  expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('Approval is blocked by the following recorded unavailable periods')
  expect(screen.getByRole('alert')).not.toHaveTextContent('Latest booking status: pending')
  expect(screen.getByRole('alert')).not.toHaveTextContent('The following recorded unavailable periods conflict')
  expect(card).toHaveTextContent('Unavailability Reason')
  expect(card).not.toHaveTextContent('Setup Period')
  expect(card).not.toHaveTextContent('Booking Name')
  expect(card.querySelector('strong.booking-time-conflict')).toHaveTextContent('11:30 am')
  const currentPanel = screen.getByRole('region', { name: 'Event Requirements' })
  expect(currentPanel.querySelector('strong.booking-time-conflict')).toHaveTextContent('12:00 pm')
  expect(screen.getByLabelText('Status: pending')).toBeInTheDocument()
  if (failRefresh) expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
  else {
    await user.click(screen.getByRole('button', { name: 'Refresh booking details' }))
    expect(screen.queryByRole('group', { name: 'Unavailable period: Maintenance' })).not.toBeInTheDocument()
    expect(currentPanel.querySelector('.booking-time-conflict')).toBeNull()
    expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Reject Booking' })).toBeEnabled()
    cleanup()
    page('/venue-staff/bookings/b1?from=booking-approvals')
    expect(await screen.findByRole('button', { name: 'Approve Booking' })).toBeEnabled()
  }
})

it('shows combined booking and unavailable-period conflicts without highlighting buffer-only periods', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) => init.method === 'PATCH'
    ? response({ message: 'Booking and unavailability conflicts', conflicts: [{ bookingId: 'b2', eventName: 'Other booking',
      startDatetime: booking.event.startDatetime, endDatetime: booking.event.endDatetime }], unavailablePeriods: [
      { unavailabilityId: 'u1', reason: 'Maintenance', startDatetime: '2026-09-24T14:00:00Z', endDatetime: '2026-09-24T14:45:00Z' },
      { unavailabilityId: 'u2', reason: 'Cleaning', startDatetime: '2026-09-24T19:00:00Z', endDatetime: '2026-09-24T20:00:00Z' },
    ] }, 409) : response(booking)))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByRole('group', { name: 'Conflicting booking: Other booking' })).toBeInTheDocument()
  for (const reason of ['Maintenance', 'Cleaning']) {
    expect(screen.getByRole('group', { name: `Unavailable period: ${reason}` }).querySelector('.booking-time-conflict')).toBeNull()
  }
})

it.each(['approved', 'cancelled'] as const)('reconciles a stale approval conflict to the backend %s status', async status => {
  let attempted = false
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') { attempted = true; return response({ message: 'This booking is no longer pending.' }, 409) }
    return response({ ...booking, status: attempted ? status : 'pending' })
  }))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByLabelText('Status: ' + status)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('no longer pending')
})

it('removes a booking deleted between review and approval', async () => {
  let attempted = false
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') attempted = true
    return attempted ? response({ message: 'Booking not found' }, 404) : response(booking)
  }))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('could not be found')
  expect(screen.queryByLabelText('Status: pending')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
})

it('requires a successful refresh after a lost response and failed read, then shows the committed status', async () => {
  let attempted = false
  let online = false
  const fetch = vi.fn().mockImplementation(async (_url: string, init: RequestInit) => {
    if (init.method === 'PATCH') { attempted = true; throw new TypeError('Lost response') }
    if (attempted && !online) throw new TypeError('Offline')
    return response({ ...booking, status: attempted ? 'approved' : 'pending' })
  })
  vi.stubGlobal('fetch', fetch)
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Approve Booking' }))
  await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Refresh before attempting a booking decision again')
  expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
  expect(screen.getByLabelText('Status: pending')).toBeInTheDocument()
  online = true
  await user.click(screen.getByRole('button', { name: 'Refresh booking details' }))
  expect(await screen.findByLabelText('Status: approved')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
  expect(fetch.mock.calls.filter(call => call[1].method === 'PATCH')).toHaveLength(1)
})

it('places the approval action in the venue information header', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(booking)))
  page('/venue-staff/bookings/b1?from=booking-approvals')
  const button = await screen.findByRole('button', { name: 'Approve Booking' })
  const heading = screen.getByRole('heading', { name: 'Venue Information' })
  expect(button.closest('.booking-details-page__section-header')).toBe(heading.parentElement?.parentElement)
  expect(heading.parentElement?.parentElement).toHaveClass('booking-details-page__section-header')
  expect(within(screen.getByRole('region', { name: 'Event Requirements' })).getByLabelText('Status: pending')).toBeInTheDocument()
  expect(heading.parentElement).not.toHaveTextContent('pending')
})

it('requires a reason, cancels without writing, and displays the saved rejection', async () => {
  let current = booking
  const fetch = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (options.method === 'PATCH') {
      expect(url).toMatch(/\/reject$/)
      expect(JSON.parse(options.body as string)).toEqual({
        reason: 'Maintenance', alternativeVenueId: null, alternativeArrangement: null,
      })
      current = { ...booking, status: 'rejected', rejectReason: 'Maintenance' }
      return response(current)
    }
    return response(url.endsWith('/bookings') ? [current] : current)
  })
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Reject Booking' }))
  await user.click(screen.getByRole('button', { name: 'Cancel' }))
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
  await user.click(screen.getByRole('button', { name: 'Reject Booking' }))
  await user.type(screen.getByRole('textbox', { name: 'Rejection reason' }), '   ')
  await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))
  expect(screen.getByRole('alert')).toHaveTextContent('A rejection reason is required.')
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
  await user.type(screen.getByRole('textbox', { name: 'Rejection reason' }), 'Maintenance  ')
  await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))
  const requirements = await screen.findByRole('region', { name: 'Event Requirements' })
  const rejectionBanner = screen.getByRole('status', { name: 'Rejection reason' })
  expect(requirements).not.toContainElement(rejectionBanner)
  expect(rejectionBanner).toHaveClass('booking-approval__confirmation')
  expect(within(rejectionBanner).getByText('Rejection Reason')).toBeInTheDocument()
  expect(within(rejectionBanner).getByText('Maintenance')).toBeInTheDocument()
  expect(within(requirements).getByLabelText('Status: rejected')).toBeInTheDocument()
  expect(screen.getAllByLabelText('Status: rejected')).toHaveLength(1)
  expect(screen.getByLabelText('Status: rejected')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reject Booking' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve Booking' })).not.toBeInTheDocument()
})

it('refreshes after a lost rejection response and displays the committed reason', async () => {
  let rejected = false
  const fetch = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (options.method === 'PATCH') { rejected = true; throw new Error('Lost response') }
    const current = rejected ? { ...booking, status: 'rejected', rejectReason: 'Unavailable' } : booking
    return response(url.endsWith('/bookings') ? [current] : current)
  })
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Reject Booking' }))
  await user.type(screen.getByRole('textbox', { name: 'Rejection reason' }), 'Unavailable')
  await user.click(screen.getByRole('button', { name: 'Confirm rejection' }))
  expect(await screen.findByText('Unavailable')).toBeInTheDocument()
  expect(screen.getByLabelText('Status: rejected')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reject Booking' })).not.toBeInTheDocument()
  expect(fetch.mock.calls.filter(call => call[1].method === 'PATCH')).toHaveLength(1)
})
