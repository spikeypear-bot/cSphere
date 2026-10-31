// @stories DEV16 VS-C2
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { BookingTimingEditor } from './BookingTimingEditor'
import type { VenueBookingDto } from '../../types/venueBooking'
import { seedSession, stubApi, jsonResponse } from '../../test/apiStubs'
import { apiClient } from '../../lib/apiClient'

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
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
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
it('preserves unsaved duration edits when background refresh returns the same saved timings', () => {
  const props = { onSaved: vi.fn(), disabled: false }
  const { rerender } = render(<BookingTimingEditor booking={booking} {...props} />)
  fireEvent.change(screen.getByLabelText('Setup (minutes)'), { target: { value: '75' } })
  rerender(<BookingTimingEditor booking={JSON.parse(JSON.stringify(booking))} {...props} />)
  expect(screen.getByLabelText('Setup (minutes)')).toHaveValue(75)
})
it('shows only the timing editor and sends edited durations with a stale-write guard', async () => {
  seedSession('venue-staff')
  const onSaved = vi.fn()
  const calls = stubApi({ 'PUT /api/venue-bookings/b1/timing': () => jsonResponse(200, booking) })
  render(<BookingTimingEditor booking={booking} onSaved={onSaved} disabled={false} />)
  expect(screen.queryByRole('heading', { name: 'Booking Timings (SGT)' })).not.toBeInTheDocument()
  expect(screen.queryByText('30 / 15 minutes')).not.toBeInTheDocument()
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

describe('DEV16 booking timing coverage', () => {
  it.each(['', 'x'.repeat(2001)])('DEV16 (negative): an invalid reason prevents saving (%s)', async note => {
    const put = vi.spyOn(apiClient, 'put')
    render(<BookingTimingEditor booking={booking} onSaved={vi.fn()} disabled={false} />)
    fireEvent.change(screen.getByLabelText('Reason for booking timings'), { target: { value: note } })
    fireEvent.click(screen.getByRole('button', { name: 'Save booking timings' }))
    expect(screen.getByRole('alert')).toHaveTextContent('1–2,000 characters')
    expect(put).not.toHaveBeenCalled()
  })
  it('DEV16 (negative): disabled, invalid and in-flight forms cannot submit another write', async () => {
    const put = vi.spyOn(apiClient, 'put').mockReturnValue(new Promise(() => {}))
    const props = { onSaved: vi.fn() }
    const page = render(<BookingTimingEditor booking={booking} {...props} disabled />)
    const form = screen.getByRole('button', { name: 'Save booking timings' }).closest('form')!
    fireEvent.submit(form)
    expect(put).not.toHaveBeenCalled()
    page.rerender(<BookingTimingEditor booking={booking} {...props} disabled={false} />)
    fireEvent.change(screen.getByLabelText('Turnaround (minutes)'), { target: { value: '1.5' } })
    fireEvent.submit(form)
    expect(screen.getByRole('alert')).toHaveTextContent('whole minutes')
    expect(put).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Turnaround (minutes)'), { target: { value: '0' } })
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(put).toHaveBeenCalledTimes(1)
    expect(props.onSaved).not.toHaveBeenCalled()
  })
  it.each([new Error('Connection lost'), 'offline'])('DEV16 (failure): unexpected failures retain proposed values (%s)', async cause => {
    vi.spyOn(apiClient, 'put').mockRejectedValue(cause)
    const onSaved = vi.fn()
    render(<BookingTimingEditor booking={booking} onSaved={onSaved} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'Save booking timings' }))
    expect(await screen.findByText(cause instanceof Error ? cause.message : 'Could not save booking timings.')).toBeInTheDocument()
    expect(screen.getByLabelText('Setup (minutes)')).toHaveValue(30)
    expect(onSaved).not.toHaveBeenCalled()
  })
  it('DEV16 (conflict): server occupancy details include explicit effective periods and unavailable reasons', async () => {
    seedSession('venue-staff')
    stubApi({ 'PUT /api/venue-bookings/b1/timing': () => jsonResponse(409, { message: 'Blocked',
      conflicts: [{ bookingId: 'b2', eventName: 'Other event', startDatetime: '2030-04-01T00:00:00+08:00',
        endDatetime: '2030-04-01T02:00:00+08:00', effectiveStart: '2030-03-31T23:30:00+08:00', effectiveEnd: '2030-04-01T02:30:00+08:00' }],
      unavailablePeriods: [{ unavailabilityId: 'u1', reason: 'Repairs', startDatetime: '2030-04-01T00:00:00+08:00', endDatetime: '2030-04-01T02:00:00+08:00' }] }) })
    render(<BookingTimingEditor booking={booking} onSaved={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'Save booking timings' }))
    expect(await screen.findByText(/Other event — occupied/)).toHaveTextContent('11:30 pm')
    expect(screen.getByText(/Repairs —/)).toBeInTheDocument()
  })
  it('DEV16 (boundary): missing timings and terminal bookings have no editor; refreshed empty notes clear old values', () => {
    const props = { onSaved: vi.fn(), disabled: false }
    const page = render(<BookingTimingEditor booking={booking} {...props} />)
    page.rerender(<BookingTimingEditor booking={{ ...booking, timing: undefined }} {...props} />)
    expect(screen.queryByText('Edit booking timings')).not.toBeInTheDocument()
    page.rerender(<BookingTimingEditor booking={{ ...booking, timing: { ...booking.timing!, timingNote: null } }} {...props} />)
    expect(screen.getByLabelText('Reason for booking timings')).toHaveValue('')
    for (const status of ['rejected', 'cancelled', 'changed'] as const) {
      page.rerender(<BookingTimingEditor booking={{ ...booking, status }} {...props} />)
      expect(screen.queryByText('Edit booking timings')).not.toBeInTheDocument()
    }
    page.rerender(<BookingTimingEditor booking={{ ...booking, status: 'attention' }} {...props} />)
    expect(screen.getByText('Edit booking timings')).toBeInTheDocument()
  })
})
