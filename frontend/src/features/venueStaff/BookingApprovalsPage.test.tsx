import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import App from '../../App'
import { AUTH_STORAGE_KEY } from '../../lib/authTokens'
import type { VenueBookingDto } from '../../types/venueBooking'

// Isolate booking requests from the shell's independent notification polling.
vi.mock('../notifications/NotificationBell', () => ({ NotificationBell: () => null }))

const booking: VenueBookingDto = {
  bookingId: 'b1', status: 'pending',
  venue: { venueId: 'v1', venueAddress: 'Seminar Room', venueCapacity: 200,
    supportedLayouts: ['classroom'], venueAccessibilities: [], venueFacilities: [],
    operatingInformation: 'Daily', additionalInformation: null },
  event: { eventId: 'e1', eventName: 'Workshop', startDatetime: '2026-09-24T15:00:00Z',
    endDatetime: '2026-09-24T18:00:00Z', expectedAttendance: 120,
    venueRequirements: 'Classroom seating', accessibilityNeeds: [], equipmentRequirements: null },
}
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) }) as Response
function page(role = 'venue-staff', path = '/venue-staff/booking-approvals') {
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
    accessToken: 'access-1', refreshToken: 'refresh-1', username: 'vs1', role, organisation: 'ConnectSphere',
  }))
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.localStorage.clear() })

it('renders pending summaries with status and opens the selected existing booking details', async () => {
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/venue-staff/booking-requests')) return response([booking,
      ...(['confirmed', 'changed', 'rejected', 'cancelled'] as const).map(status => ({ ...booking, bookingId: status, status, event: { ...booking.event, eventName: status } })),
    ])
    if (url.endsWith('/venue-bookings/b1')) return response(booking)
    if (url.endsWith('/venues/v1/bookings')) return response([booking])
    throw new Error('Unexpected URL: ' + url)
  })
  vi.stubGlobal('fetch', fetch); page()
  expect(await screen.findByRole('heading', { name: 'Workshop' })).toBeInTheDocument()
  expect(screen.getByText('Status: Pending')).toBeInTheDocument()
  expect(screen.getByText('Seminar Room')).toBeInTheDocument()
  expect(screen.getByText('120 people')).toBeInTheDocument()
  expect(screen.getByText('Classroom seating')).toBeInTheDocument()
  expect(screen.getByText(/24 Sept? 2026.*11:00 pm/i)).toBeInTheDocument()
  expect(screen.getByText(/25 Sept? 2026.*02:00 am/i)).toBeInTheDocument()
  const link = screen.getByRole('link', { name: 'View booking details' })
  expect(link).toHaveAttribute('href', '/venue-staff/bookings/b1?from=booking-approvals')
  for (const status of ['confirmed', 'changed', 'rejected', 'cancelled']) {
    expect(screen.queryByRole('heading', { name: status })).not.toBeInTheDocument()
  }
  await userEvent.click(link)
  expect(await screen.findByRole('region', { name: 'Event requirements' })).toHaveTextContent('Workshop')
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
})

it('keeps initial loading separate from an empty queue', async () => {
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
  page()
  expect(screen.getByRole('status')).toHaveTextContent('Loading pending booking requests')
  expect(screen.queryByText('No pending booking requests found.')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Refresh pending booking requests' })).toBeDisabled()
  await act(async () => { finish(response([])) })
  expect(await screen.findByText('No pending booking requests found.')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Pending booking requests' })).toBeInTheDocument()
})

it('shows retrieval errors separately from empty state and retries successfully', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ message: 'Service unavailable' }, 503))
    .mockResolvedValue(response([booking])))
  page()
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load pending booking requests')
  expect(screen.queryByText('No pending booking requests found.')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('Workshop')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('shows a refresh state and replaces the list with the latest pending results', async () => {
  let finish!: (value: Response) => void
  const fetch = vi.fn().mockResolvedValueOnce(response([booking]))
    .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve }))
  vi.stubGlobal('fetch', fetch); page()
  await screen.findByText('Workshop')
  await userEvent.click(screen.getByRole('button', { name: 'Refresh pending booking requests' }))
  expect(screen.getByRole('status')).toHaveTextContent('Refreshing pending booking requests')
  expect(screen.getByRole('button', { name: 'Refresh pending booking requests' })).toBeDisabled()
  expect(screen.queryByRole('link', { name: 'View booking details' })).not.toBeInTheDocument()
  await act(async () => { finish(response([])) })
  expect(await screen.findByText('No pending booking requests found.')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('uses a placeholder for missing requirements and shortens long requirements', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([
    { ...booking, event: { ...booking.event, venueRequirements: '  ' } },
    { ...booking, bookingId: 'b2', event: { ...booking.event, eventName: 'Long requirements', venueRequirements: 'A'.repeat(500) } },
  ])))
  page()
  expect(await screen.findByText('Not specified')).toBeInTheDocument()
  expect(screen.getByText('A'.repeat(200) + '…')).toBeInTheDocument()
  expect(screen.queryByText('A'.repeat(500))).not.toBeInTheDocument()
})

it('keeps the queue behind the existing Venue Staff route gate', async () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch); page('attendee')
  expect(await screen.findByRole('alert')).toHaveTextContent('Access denied')
  expect(screen.queryByRole('heading', { name: 'Pending booking requests' })).not.toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})

it.each(['confirmed', 'changed', 'rejected', 'cancelled'] as const)(
  'reads fresh details when a pending booking becomes %s and refreshes the queue on return', async status => {
    let queueReads = 0
    const fetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.endsWith('/venue-staff/booking-requests')) return response(++queueReads === 1 ? [booking] : [])
      if (url.endsWith('/venue-bookings/b1')) return response({ ...booking, status,
        event: { ...booking.event, expectedAttendance: 150, venueRequirements: 'Updated seating' } })
      throw new Error('Unexpected URL: ' + url)
    })
    vi.stubGlobal('fetch', fetch); page()
    await userEvent.click(await screen.findByRole('link', { name: 'View booking details' }))
    expect(await screen.findByRole('region', { name: 'Event requirements' })).toHaveTextContent('150 people')
    expect(screen.getByText('Updated seating')).toBeInTheDocument()
    expect(screen.getByText(`Status: ${status}`)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(`This booking request is no longer pending. Its current status is ${status}.`)
    expect(screen.queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Back to Pending Booking Requests' }))
    expect(await screen.findByText('No pending booking requests found.')).toBeInTheDocument()
    expect(queueReads).toBe(2)
    expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
    expect(fetch.mock.calls.some(call => call[0].endsWith('/venues/v1/bookings'))).toBe(false)
  },
)

it('preserves queue context on direct navigation and reopening with current detail data', async () => {
  let attendance = 120
  const fetch = vi.fn().mockImplementation(async () => response({ ...booking,
    event: { ...booking.event, expectedAttendance: attendance } }))
  vi.stubGlobal('fetch', fetch)
  const path = '/venue-staff/bookings/b1?from=booking-approvals'
  const view = page('venue-staff', path)
  expect(await screen.findByText('120 people')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Back to Pending Booking Requests' })).toHaveAttribute('href', '/venue-staff/booking-approvals')
  expect(screen.queryByText(/no longer pending/)).not.toBeInTheDocument()
  view.unmount()
  attendance = 180
  page('venue-staff', path)
  expect(await screen.findByText('180 people')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledTimes(2)
})

it.each([
  [404, 'missing', 'Venue booking not found'],
  [400, 'not-a-uuid', 'Venue and booking IDs must be valid UUIDs.'],
])('handles HTTP %s detail errors with queue navigation and retry', async (status, id, message) => {
  const fetch = vi.fn().mockResolvedValueOnce(response({ message }, status as number))
    .mockResolvedValue(response(booking))
  vi.stubGlobal('fetch', fetch); page('venue-staff', `/venue-staff/bookings/${id}?from=booking-approvals`)
  expect(await screen.findByRole('alert')).toHaveTextContent(message as string)
  expect(screen.queryByRole('region', { name: 'Event requirements' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Back to Pending Booking Requests' })).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('region', { name: 'Event requirements' })).toHaveTextContent('Workshop')
})

it('ignores a late detail response after returning to the queue', async () => {
  let finish!: (value: Response) => void
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/venue-staff/booking-requests')) return response([booking])
    return new Promise<Response>(resolve => { finish = resolve })
  })
  vi.stubGlobal('fetch', fetch); page()
  await userEvent.click(await screen.findByRole('link', { name: 'View booking details' }))
  expect(screen.getByRole('status')).toHaveTextContent('Loading booking details')
  expect(screen.queryByRole('region', { name: 'Event requirements' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('link', { name: 'Back to Pending Booking Requests' }))
  await screen.findByRole('link', { name: 'View booking details' })
  await act(async () => { finish(response({ ...booking, event: { ...booking.event, eventName: 'Late response' } })) })
  expect(screen.getByRole('heading', { name: 'Pending booking requests' })).toBeInTheDocument()
  expect(screen.queryByText('Late response')).not.toBeInTheDocument()
})
