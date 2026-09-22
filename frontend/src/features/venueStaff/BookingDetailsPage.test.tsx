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
  const requirements = await screen.findByRole('region', { name: 'Event requirements' })
  expect(within(requirements).getByText('120 people')).toBeInTheDocument()
  expect(within(requirements).getByText('Classroom seating')).toBeInTheDocument()
  expect(within(requirements).getAllByText('Not specified')).toHaveLength(2)
  expect(within(requirements).getByText(/24 Sept? 2026/)).toBeInTheDocument()
  expect(within(requirements).getByText(/25 Sept? 2026/)).toBeInTheDocument()
  expect(within(requirements).queryByRole('textbox')).not.toBeInTheDocument()
  expect(within(requirements).queryByRole('button')).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Venue information' })).toHaveTextContent('200 people')
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
  expect(screen.queryByRole('region', { name: 'Event requirements' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('120 people')).toBeInTheDocument()
})

it('preserves explicit no accessibility requirements and displays existing equipment requirements', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => response(url.endsWith('/bookings') ? [booking] : { ...booking,
    event: { ...booking.event, accessibilityNeeds: ['none'], equipmentRequirements: 'Projector' } })))
  page('/venue-staff/bookings/b1')
  const requirements = await screen.findByRole('region', { name: 'Event requirements' })
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
  const venuePanel = screen.getByRole('region', { name: 'Venue information' })
  const requirementsPanel = screen.getByRole('region', { name: 'Event requirements' })
  const navigation = screen.getByRole('navigation', { name: 'Venue bookings' })
  const next = screen.getByRole('button', { name: 'Next' })
  next.focus()
  const user = userEvent.setup()
  await user.keyboard('{Enter}')
  expect(next).toHaveFocus()
  await user.keyboard('{Enter}')
  expect(screen.getByRole('region', { name: 'Venue information' })).toBe(venuePanel)
  expect(screen.getByRole('region', { name: 'Event requirements' })).toBe(requirementsPanel)
  expect(screen.getByRole('navigation', { name: 'Venue bookings' })).toBe(navigation)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toHaveAttribute('aria-disabled', 'true')
  await act(async () => { finish(response(second)) })
  expect(await screen.findByText('Second event')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toBe(next)
  expect(next).toHaveFocus()
  await user.keyboard(' ')
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/b2'))).toHaveLength(1)
  expect(screen.getByRole('region', { name: 'Venue information' })).toBe(venuePanel)
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
  const venue = screen.getByRole('region', { name: 'Venue information' })
  const requirements = screen.getByRole('region', { name: 'Event requirements' })
  const navigation = screen.getByRole('navigation', { name: 'Venue bookings' })
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Next' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Showing the previously loaded booking')
  expect(screen.getByRole('region', { name: 'Event requirements' })).toBe(requirements)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Venue information' })).toBe(venue)
  expect(screen.getByRole('region', { name: 'Event requirements' })).toBe(requirements)
  expect(screen.getByRole('navigation', { name: 'Venue bookings' })).toBe(navigation)
  expect(screen.getByText('Workshop')).toBeInTheDocument()
  await act(async () => { finish(response(second)) })
  expect(await screen.findByText('Recovered event')).toBeInTheDocument()
  expect(screen.getByText('Booking 2 of 2')).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Event requirements' })).toBe(requirements)
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/venues/v1/bookings'))).toHaveLength(1)
  expect(fetch.mock.calls.every(call => call[1].method === 'GET')).toBe(true)
})
