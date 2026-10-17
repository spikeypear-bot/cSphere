// @stories VS-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { BookingTimingEditor } from './BookingTimingEditor'
import type { VenueBookingDto } from '../../types/venueBooking'
import { seedSession, stubApi, jsonResponse } from '../../test/apiStubs'

const booking: VenueBookingDto = {
  bookingId: 'b1', status: 'approved',
  venue: { venueId: 'v1', venueAddress: 'Hall', venueCapacity: 100, supportedLayouts: [], venueAccessibilities: [],
    venueFacilities: [], operatingInformation: 'Daily', additionalInformation: null, setupMinutes: 120, turnaroundMinutes: 120 },
  event: { eventId: 'e1', eventName: 'Workshop', startDatetime: '2030-04-01T00:15:00+08:00',
    endDatetime: '2030-04-01T01:00:00+08:00', expectedAttendance: 50, venueRequirements: 'Early setup', accessibilityNeeds: [], equipmentRequirements: null },
  timing: { setupDurationMinutes: 30, turnaroundDurationMinutes: 15, startDatetime: '2030-04-01T00:15:00+08:00',
    endDatetime: '2030-04-01T01:00:00+08:00', effectiveStart: '2030-03-31T23:45:00+08:00', effectiveEnd: '2030-04-01T01:15:00+08:00',
    timingNote: 'Early crew access', conflicts: [], editable: true },
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })
it('refreshes the editable durations when the server booking is refreshed', () => {
  const props = { onSaved: vi.fn(), disabled: false }
  const { rerender } = render(<BookingTimingEditor booking={booking} {...props} />)
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '90' } })
  rerender(<BookingTimingEditor booking={{ ...booking, timing: { ...booking.timing!, setupDurationMinutes: 45,
    turnaroundDurationMinutes: 20, timingNote: 'Updated crew access' } }} {...props} />)
  expect(screen.getByLabelText('Setup (minutes)')).toHaveValue(45)
  expect(screen.getByLabelText('Turnaround (minutes)')).toHaveValue(20)
  expect(screen.getByLabelText('Reason for booking timings')).toHaveValue('Updated crew access')
})
it('shows saved snapshots and sends edited durations with a stale-write guard', async () => {
  seedSession('venue-staff')
  const onSaved = vi.fn()
  const calls = stubApi({ 'PUT /api/venue-bookings/b1/timing': () => jsonResponse(200, booking) })
  render(<BookingTimingEditor booking={booking} onSaved={onSaved} disabled={false} />)
  expect(screen.getByText('30 / 15 minutes')).toBeInTheDocument()
  fireEvent.click(screen.getByText('Edit booking timings'))
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '45' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save booking timings' }))
  expect(await screen.findByText(/Booking timings saved/)).toBeInTheDocument()
  expect(calls[0].body).toEqual({ setupDurationMinutes: 45, turnaroundDurationMinutes: 15,
    timingNote: 'Early crew access', previousSetupDurationMinutes: 30, previousTurnaroundDurationMinutes: 15 })
  expect(onSaved).toHaveBeenCalledWith(booking)
})
it('retains proposed inputs and identifies occupied conflicts after a rejected save', async () => {
  seedSession('venue-staff')
  const onSaved = vi.fn()
  stubApi({ 'PUT /api/venue-bookings/b1/timing': () => jsonResponse(409, { message: 'New overlap blocked.',
    conflicts: [{ bookingId: 'b2', eventName: 'Earlier booking', startDatetime: '2030-03-31T22:00:00+08:00', endDatetime: '2030-04-01T00:00:00+08:00' }] }) })
  render(<BookingTimingEditor booking={booking} onSaved={onSaved} disabled={false} />)
  fireEvent.click(screen.getByText('Edit booking timings'))
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '60' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save booking timings' }))
  expect(await screen.findByText('New overlap blocked.')).toBeInTheDocument()
  expect(screen.getByText(/Earlier booking — occupied/)).toBeInTheDocument()
  expect(screen.getByLabelText('Setup (minutes)')).toHaveValue(60)
  expect(onSaved).not.toHaveBeenCalled()
})
it('blocks invalid input and hides editing for historical records', () => {
  render(<BookingTimingEditor booking={booking} onSaved={vi.fn()} disabled={false} />)
  fireEvent.click(screen.getByText('Edit booking timings'))
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '-1' } })
  expect(screen.getByRole('button', { name: 'Save booking timings' })).toBeDisabled()
  cleanup()
  render(<BookingTimingEditor booking={{ ...booking, timing: { ...booking.timing!, editable: false } }} onSaved={vi.fn()} disabled={false} />)
  expect(screen.queryByText('Edit booking timings')).not.toBeInTheDocument()
})

it('previews durations around the booking window when it differs from the event', () => {
  render(<BookingTimingEditor booking={{ ...booking, event: { ...booking.event,
    startDatetime: '2030-04-01T10:00:00+08:00', endDatetime: '2030-04-01T12:00:00+08:00' } }} onSaved={vi.fn()} disabled={false} />)
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '45' } })
  const preview = screen.getByText(/Proposed occupied time/)
  expect(preview).toHaveTextContent('11:30 pm')
  expect(preview).toHaveTextContent('01:15')
})
