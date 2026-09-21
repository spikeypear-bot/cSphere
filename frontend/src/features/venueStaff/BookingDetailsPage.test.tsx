import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
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
    .mockResolvedValueOnce(response(booking))
  vi.stubGlobal('fetch', fetch); page('/venue-staff/bookings/b1')
  expect(await screen.findByRole('alert')).toHaveTextContent('Venue booking not found')
  expect(screen.queryByRole('region', { name: 'Event requirements' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('120 people')).toBeInTheDocument()
})

it('preserves explicit no accessibility requirements and displays existing equipment requirements', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...booking,
    event: { ...booking.event, accessibilityNeeds: ['none'], equipmentRequirements: 'Projector' } })))
  page('/venue-staff/bookings/b1')
  const requirements = await screen.findByRole('region', { name: 'Event requirements' })
  expect(within(requirements).getByText('No accessibility requirements')).toBeInTheDocument()
  expect(within(requirements).getByText('Projector')).toBeInTheDocument()
})
