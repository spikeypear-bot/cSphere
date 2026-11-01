// @stories DEV16 VS03 VS04
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { BookingApproval } from './BookingApproval'
import { apiClient } from '../../lib/apiClient'
import type { VenueBookingDto } from '../../types/venueBooking'

vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(),
  apiClient: { get: vi.fn(), patch: vi.fn() } }))
const booking: VenueBookingDto = { bookingId: 'request', status: 'pending',
  venue: { venueId: 'room', venueAddress: 'Hall', venueCapacity: 100, venueFacilities: [], venueAccessibilities: [],
    supportedLayouts: [], operatingInformation: '', additionalInformation: null },
  event: { eventId: 'event', eventName: 'Workshop', startDatetime: '2027-04-01T02:00:00Z', endDatetime: '2027-04-01T04:00:00Z',
    expectedAttendance: 50, venueRequirements: '', accessibilityNeeds: [], equipmentRequirements: null },
}
function mount(current = booking) {
  const props = { booking: current, onApproved: vi.fn(), onUnavailable: vi.fn(), conflicts: [], unavailablePeriods: [], onConflicts: vi.fn() }
  return { ...render(<BookingApproval {...props} />), ...props }
}
afterEach(() => { cleanup(); vi.resetAllMocks() })

describe('DEV16 booking decision recovery coverage', () => {
  it('DEV16 (failure): alternative-venue failure can be retried without closing the rejection form', async () => {
    vi.mocked(apiClient.get).mockRejectedValueOnce('offline').mockResolvedValue([booking.venue,
      { ...booking.venue, venueId: 'alternative', venueAddress: 'Other hall' }])
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Reject Booking' }))
    expect(await screen.findByText('Could not load alternative venues. You can still reject this request without a venue suggestion.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading venues' }))
    expect(await screen.findByRole('option', { name: 'Other hall' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm rejection' })).toBeEnabled()
    expect(apiClient.patch).not.toHaveBeenCalled()
    expect(props.onApproved).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    fireEvent.click(screen.getByRole('button', { name: 'Reject Booking' }))
    expect(apiClient.get).toHaveBeenCalledTimes(2)
  })
  it('DEV16 (failure): an unexpected decision failure requires a current read before allowing a retry', async () => {
    vi.mocked(apiClient.patch).mockRejectedValue('offline')
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Read failed')).mockResolvedValue({ ...booking, status: 'approved' })
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not confirm the booking decision result.')
    expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh booking details' }))
    await act(async () => {})
    expect(props.onApproved).toHaveBeenCalledWith({ ...booking, status: 'approved' })
    expect(apiClient.patch).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (cross-cutting): leaving during an outcome read cannot update another booking page', async () => {
    vi.mocked(apiClient.patch).mockRejectedValue(new Error('Lost response'))
    let rejectRead!: (error: Error) => void
    vi.mocked(apiClient.get).mockReturnValue(new Promise((_, reject) => { rejectRead = reject }))
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await act(async () => {})
    expect(apiClient.get).toHaveBeenCalledWith('/venue-bookings/request', { cache: 'no-store' })
    props.unmount()
    await act(async () => { rejectRead(new Error('Offline')) })
    expect(props.onApproved).not.toHaveBeenCalled()
    expect(props.onUnavailable).not.toHaveBeenCalled()
  })
  it('DEV16 (conflict): two batched confirmation clicks make exactly one booking decision', async () => {
    vi.mocked(apiClient.patch).mockReturnValue(new Promise(() => {}))
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    const confirm = screen.getByRole('button', { name: 'Confirm approval' })
    act(() => { fireEvent.click(confirm); fireEvent.click(confirm) })
    expect(apiClient.patch).toHaveBeenCalledExactlyOnceWith('/venue-bookings/request/approve', undefined)
  })
  it('DEV16 (happy): approval confirmation shows saved booking buffers before committing the decision', async () => {
    const current = { ...booking, timing: { setupDurationMinutes: 30, turnaroundDurationMinutes: 45,
      startDatetime: booking.event.startDatetime, endDatetime: booking.event.endDatetime,
      effectiveStart: '2027-04-01T01:30:00Z', effectiveEnd: '2027-04-01T04:45:00Z', timingNote: 'Crew access', conflicts: [] } }
    vi.mocked(apiClient.patch).mockResolvedValue({ ...current, status: 'approved' })
    const props = mount(current)
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    expect(screen.getByText('30 / 45 minutes')).toBeInTheDocument()
    expect(screen.getByText('Venue Occupied (SGT)').nextElementSibling).toHaveTextContent('09:30 am')
    expect(apiClient.patch).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await act(async () => {})
    expect(props.onApproved).toHaveBeenCalledExactlyOnceWith({ ...current, status: 'approved' })
  })
  it('DEV16 (failure): a failed manual refresh never re-enables a decision against an unknown current state', async () => {
    vi.mocked(apiClient.patch).mockRejectedValue(new Error('Lost response'))
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Offline'))
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'Refresh booking details' }))
    await act(async () => {})
    expect(screen.getByRole('alert')).toHaveTextContent('Could not refresh booking details.')
    expect(screen.getByRole('button', { name: 'Approve Booking' })).toBeDisabled()
    expect(props.onApproved).not.toHaveBeenCalled()
  })
  it.each([false, true])('DEV16 (cross-cutting): late alternative-venue responses cannot update a departed page (%s)', async failed => {
    let resolve!: (value: unknown) => void, reject!: (cause: unknown) => void
    vi.mocked(apiClient.get).mockReturnValue(new Promise((done, fail) => { resolve = done; reject = fail }))
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Reject Booking' }))
    props.unmount()
    await act(async () => { if (failed) reject(new Error('Offline')); else resolve([booking.venue]) })
    expect(props.onApproved).not.toHaveBeenCalled()
    expect(apiClient.patch).not.toHaveBeenCalled()
  })
  it.each([false, true])('DEV16 (cross-cutting): late decision outcomes cannot change a departed page (%s)', async failed => {
    let resolve!: (value: unknown) => void, reject!: (cause: unknown) => void
    vi.mocked(apiClient.patch).mockReturnValue(new Promise((done, fail) => { resolve = done; reject = fail }))
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    props.unmount()
    await act(async () => { if (failed) reject(new Error('Offline')); else resolve({ ...booking, status: 'approved' }) })
    expect(props.onApproved).not.toHaveBeenCalled()
    expect(apiClient.get).not.toHaveBeenCalled()
  })
  it('DEV16 (conflict): duplicate refresh clicks issue one read and a late read cannot update a departed page', async () => {
    vi.mocked(apiClient.patch).mockRejectedValue(new Error('Lost response'))
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Offline'))
    let resolve!: (value: unknown) => void
    const props = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await act(async () => {})
    vi.mocked(apiClient.get).mockReturnValue(new Promise(done => { resolve = done }))
    const refresh = screen.getByRole('button', { name: 'Refresh booking details' })
    act(() => { fireEvent.click(refresh); fireEvent.click(refresh) })
    expect(apiClient.get).toHaveBeenCalledTimes(2)
    props.unmount()
    await act(async () => { resolve({ ...booking, status: 'approved' }) })
    expect(props.onApproved).not.toHaveBeenCalled()
  })
  it('DEV16 (conflict): a live tentative hold arriving during confirmation removes the direct approval action', () => {
    const props = mount({ ...booking, holds: [] })
    fireEvent.click(screen.getByRole('button', { name: 'Approve Booking' }))
    expect(screen.getByRole('button', { name: 'Confirm approval' })).toBeInTheDocument()
    props.rerender(<BookingApproval booking={{ ...booking, holds: [{ holdId: 'hold', status: 'active' } as NonNullable<VenueBookingDto['holds']>[number]] }}
      onApproved={props.onApproved} onUnavailable={props.onUnavailable} conflicts={[]} unavailablePeriods={[]} onConflicts={props.onConflicts} />)
    expect(screen.queryByRole('button', { name: 'Confirm approval' })).not.toBeInTheDocument()
    expect(apiClient.patch).not.toHaveBeenCalled()
  })
})
