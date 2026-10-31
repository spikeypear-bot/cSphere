// @stories DEV16 VS-C3 VS03 VS15 VS17
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { VenueHoldPanel } from './VenueHoldPanel'
import { useHoldDeadline } from './useHoldDeadline'
import { calendarEntries } from './venueCalendar'
import type { VenueHold } from '../../types/venueHold'
import type { VenueBookingDto } from '../../types/venueBooking'

vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(),
  apiClient: { get: vi.fn(), post: vi.fn(), patch: vi.fn() } }))
const hold: VenueHold = { holdId: 'h1', bookingId: 'b1', venueId: 'v1', eventId: 'e1', eventName: 'Workshop',
  reservedStart: '2027-04-01T09:30:00+08:00', reservedEnd: '2027-04-01T12:45:00+08:00',
  expiresAt: '2027-03-01T01:00:00Z', serverTime: '2027-03-01T00:00:00Z', status: 'active',
  createdBy: 'vs1', createdAt: '2027-03-01T00:00:00Z', endedAt: null }
beforeEach(() => { vi.mocked(apiClient.get).mockResolvedValue([]) })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks() })
it('validates expiry and preserves entered values after a backend conflict', async () => {
  vi.mocked(apiClient.post).mockRejectedValue(new ApiClientError('Another reservation overlaps.', 409))
  render(<VenueHoldPanel bookingId="b1" pending onChanged={vi.fn()} />)
  const input = await screen.findByLabelText('Hold expiry (Singapore time)')
  fireEvent.change(input, { target: { value: '2000-01-01T10:00' } })
  fireEvent.submit(input.closest('form')!)
  expect(screen.getByRole('alert')).toHaveTextContent('Choose an expiry later')
  expect(apiClient.post).not.toHaveBeenCalled()
  fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
  fireEvent.submit(input.closest('form')!)
  await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/venue-bookings/b1/holds', { expiresAt: '2099-03-01T10:00:00+08:00' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Another reservation overlaps.')
  expect(await screen.findByLabelText('Hold expiry (Singapore time)')).toHaveValue('2099-03-01T10:00')
})
it('creates a hold and refreshes booking details without treating it as approved', async () => {
  const changed = vi.fn()
  vi.mocked(apiClient.post).mockResolvedValue(hold)
  render(<VenueHoldPanel bookingId="b1" pending onChanged={changed} />)
  const input = await screen.findByLabelText('Hold expiry (Singapore time)')
  fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
  fireEvent.submit(input.closest('form')!)
  expect(await screen.findByText('Tentative hold created.')).toBeInTheDocument()
  expect(changed).toHaveBeenCalledOnce()
  expect(apiClient.patch).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Release Hold' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Convert Hold to Approved Booking' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Place tentative hold' })).not.toBeInTheDocument()
})

it('reuses approval conflict cards and highlights occupied times for a buffer-only conflict', async () => {
  const booking = { bookingId: 'b1', status: 'pending', event: { eventName: 'Workshop',
    startDatetime: '2027-04-01T10:00:00+08:00', endDatetime: '2027-04-01T12:00:00+08:00' },
    timing: { effectiveStart: hold.reservedStart, effectiveEnd: hold.reservedEnd } } as VenueBookingDto
  const conflict = { bookingId: 'other', eventName: 'Afternoon seminar', startDatetime: '2027-04-01T12:30:00+08:00',
    endDatetime: '2027-04-01T14:00:00+08:00', setupDurationMinutes: 15, turnaroundDurationMinutes: 0,
    effectiveStart: '2027-04-01T12:15:00+08:00', effectiveEnd: '2027-04-01T14:00:00+08:00' }
  vi.mocked(apiClient.post).mockRejectedValue(new ApiClientError('The occupied period conflicts.', 409, null, [conflict]))
  render(<VenueHoldPanel bookingId="b1" booking={booking} pending onChanged={vi.fn()} />)
  const input = await screen.findByLabelText('Hold expiry (Singapore time)')
  fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
  fireEvent.submit(input.closest('form')!)
  const card = await screen.findByRole('group', { name: 'Conflicting booking: Afternoon seminar' })
  expect(screen.getByRole('alert')).toHaveClass('notice--danger')
  expect(within(card).getByText(/12:15 pm.*02:00 pm/i)).toHaveClass('booking-time-conflict')
})
it.each(['release', 'convert'] as const)('shows expiry and performs %s for an active hold', async action => {
  vi.mocked(apiClient.get).mockResolvedValue([hold])
  vi.mocked(apiClient.patch).mockResolvedValue(action === 'release' ? { ...hold, status: 'released' } : { status: 'approved', holds: [{ ...hold, status: 'converted' }] })
  const changed = vi.fn()
  render(<VenueHoldPanel bookingId="b1" pending onChanged={changed} />)
  const button = await screen.findByRole('button', { name: action === 'release' ? 'Release Hold' : 'Convert Hold to Approved Booking' })
  expect(screen.getByText('Expires (SGT)')).toBeInTheDocument()
  fireEvent.click(button)
  await waitFor(() => expect(apiClient.patch).toHaveBeenCalledWith(`/venue-holds/h1/${action}`))
  expect(changed).toHaveBeenCalledOnce()
})
it('hides hold actions when status retrieval fails and offers retry', async () => {
  vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('Offline'))
  render(<VenueHoldPanel bookingId="b1" pending onChanged={vi.fn()} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Could not load details')
  expect(screen.queryByRole('button', { name: 'Place tentative hold' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry hold details' }))
  expect(await screen.findByLabelText('Hold expiry (Singapore time)')).toBeInTheDocument()
})
it('uses server time to remove occupancy at the exact deadline even with a wrong client clock and failed refresh', () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2030-01-01T00:00:00Z'))
  const refresh = vi.fn()
  const holds = [hold]
  const { result } = renderHook(() => useHoldDeadline(holds, refresh))
  act(() => vi.advanceTimersByTime(3599999))
  expect(result.current.has('h1')).toBe(false)
  act(() => vi.advanceTimersByTime(1))
  expect(result.current.has('h1')).toBe(true)
  expect(refresh).toHaveBeenCalledOnce()
})
it('calendar distinguishes a real hold from pending requests and applies its occupied interval once', () => {
  const schedule = { settings: { setupMinutes: 30, turnaroundMinutes: 45 }, unavailablePeriods: [], holds: [hold],
    bookings: ['b1', 'b2'].map(bookingId => ({ bookingId, eventName: 'Workshop', status: 'pending',
      startDateTime: '2027-04-01T10:00:00+08:00', endDateTime: '2027-04-01T12:00:00+08:00',
      effectiveStart: hold.reservedStart, effectiveEnd: hold.reservedEnd })) }
  const entries = calendarEntries(schedule)
  expect(entries.filter(e => e.type === 'tentative')).toEqual([expect.objectContaining({ start: hold.reservedStart, end: hold.reservedEnd, expiresAt: hold.expiresAt })])
  expect(entries.filter(e => e.type === 'pending')).toHaveLength(3)
  expect(calendarEntries(schedule, new Set(['h1'])).some(e => e.type === 'tentative')).toBe(false)
  expect(calendarEntries({ ...schedule, holds: [{ ...hold, status: 'released' }] }).some(e => e.type === 'tentative')).toBe(false)
})

it.each(['converted','released','expired'] as const)('VS-C3 (cross-cutting): %s history uses the preview card and labelled facts without decision controls',status=>{
 render(<VenueHoldPanel bookingId="b1" pending={false} initialHolds={[{...hold,status}]} onChanged={vi.fn()}/>)
 const history=screen.getByRole('list',{name:'Hold history'})
 const entry=within(history).getByRole('listitem')
 const card=entry.querySelector('.card')!
 expect(card).toHaveClass('venue-card-preview','venue-hold-panel__history-card')
 expect(card.querySelector('dl')).toHaveClass('venue-card-preview__information')
 expect(within(history).getByText('Occupied period (SGT)')).toBeInTheDocument()
 expect(within(history).getByText('Expires (SGT)')).toBeInTheDocument()
 expect(within(history).getByText('Created (SGT)')).toBeInTheDocument()
 const badge=within(history).getByText(status[0].toUpperCase()+status.slice(1))
 if(status==='converted') expect(badge).toHaveAttribute('data-booking-status','approved')
 else expect(badge).not.toHaveAttribute('data-booking-status')
 expect(screen.queryByRole('button',{name:'Release Hold'})).not.toBeInTheDocument()
 expect(screen.queryByRole('button',{name:'Convert Hold to Approved Booking'})).not.toBeInTheDocument()
 expect(apiClient.patch).not.toHaveBeenCalled()
})

describe('DEV16 hold recovery coverage', () => {
  it('DEV16 (boundary): malformed hold history cannot create occupancy or remove the creation form', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ unexpected: true })
    render(<VenueHoldPanel bookingId="b1" pending onChanged={vi.fn()} />)
    expect(await screen.findByLabelText('Hold expiry (Singapore time)')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Hold history' })).not.toBeInTheDocument()
  })
  it('DEV16 (failure): a committed hold with failed refresh keeps its saved state and warns staff', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(hold)
    render(<VenueHoldPanel bookingId="b1" pending initialHolds={[]} onChanged={vi.fn().mockRejectedValue(new Error('Offline'))} />)
    const input = screen.getByLabelText('Hold expiry (Singapore time)')
    fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
    fireEvent.submit(input.closest('form')!)
    expect(await screen.findByRole('alert')).toHaveTextContent('The hold action completed, but booking details could not be refreshed.')
    expect(screen.getByRole('button', { name: 'Release Hold' })).toBeInTheDocument()
    expect(apiClient.post).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (failure): unexpected mutation and recovery failures retain the proposed expiry without claiming success', async () => {
    vi.mocked(apiClient.post).mockRejectedValue(new Error('Offline'))
    render(<VenueHoldPanel bookingId="b1" pending initialHolds={[]} onChanged={vi.fn().mockRejectedValue(new Error('Offline'))} />)
    const input = screen.getByLabelText('Hold expiry (Singapore time)')
    fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
    fireEvent.submit(input.closest('form')!)
    expect(await screen.findByRole('alert')).toHaveTextContent('Latest booking details could not be retrieved.')
    expect(screen.getByLabelText('Hold expiry (Singapore time)')).toHaveValue('2099-03-01T10:00')
    expect(screen.queryByText('Tentative hold created.')).not.toBeInTheDocument()
  })
  it('DEV16 (conflict): repeated submission while creating a hold sends only one request', () => {
    vi.mocked(apiClient.post).mockReturnValue(new Promise(() => {}))
    render(<VenueHoldPanel bookingId="b1" pending initialHolds={[]} onChanged={vi.fn()} />)
    const input = screen.getByLabelText('Hold expiry (Singapore time)')
    fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
    fireEvent.submit(input.closest('form')!); fireEvent.submit(input.closest('form')!)
    expect(apiClient.post).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (happy): conversion without returned history updates only the active hold and stops pending decisions', async () => {
    const approved = { bookingId: 'b1', status: 'approved' } as VenueBookingDto
    vi.mocked(apiClient.patch).mockResolvedValue(approved)
    const changed = vi.fn()
    render(<VenueHoldPanel bookingId="b1" pending initialHolds={[hold, { ...hold, holdId: 'old', status: 'released' }]} onChanged={changed} />)
    fireEvent.click(screen.getByRole('button', { name: 'Convert Hold to Approved Booking' }))
    expect(await screen.findByText('Hold converted into an approved booking.')).toBeInTheDocument()
    expect(screen.getByText('Converted')).toHaveAttribute('data-booking-status', 'approved')
    expect(screen.getByText('Released')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Release Hold' })).not.toBeInTheDocument()
    expect(changed).toHaveBeenCalledWith(approved)
  })
  it('DEV16 (boundary): an unswept active hold changes to expired at its server-derived deadline', async () => {
    vi.useFakeTimers()
    const changed = vi.fn()
    render(<VenueHoldPanel bookingId="b1" pending={false} initialHolds={[hold]} onChanged={changed} />)
    expect(screen.getByText('Active')).toHaveAttribute('data-booking-status', 'tentative')
    await act(async () => { await vi.advanceTimersByTimeAsync(3600000) })
    expect(screen.getByText('Expired')).not.toHaveAttribute('data-booking-status')
    expect(changed).toHaveBeenCalledTimes(1)
  })
  it('DEV16 (boundary): long hold deadlines reschedule without expiring immediately at the browser timer limit', () => {
    vi.useFakeTimers()
    const long = { ...hold, serverTime: '2027-03-01T00:00:00Z', expiresAt: '2027-04-01T00:00:00Z' }
    const holds = [long]
    const { result } = renderHook(() => useHoldDeadline(holds))
    act(() => vi.advanceTimersByTime(2147483647))
    expect(result.current.has('h1')).toBe(false)
    act(() => vi.advanceTimersByTime(31 * 86400000 - 2147483647))
    expect(result.current.has('h1')).toBe(true)
  })
  it('DEV16 (happy): releasing the active hold preserves older history rows', async () => {
    vi.mocked(apiClient.patch).mockResolvedValue({ ...hold, status: 'released' })
    render(<VenueHoldPanel bookingId="b1" pending initialHolds={[hold, { ...hold, holdId: 'old', status: 'expired' }]} onChanged={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Release Hold' }))
    expect(await screen.findByText('Hold released.')).toBeInTheDocument()
    expect(screen.getByText('Released')).toBeInTheDocument()
    expect(screen.getByText('Expired')).toBeInTheDocument()
  })
  it('DEV16 (conflict): unavailable-period conflicts are shown alongside the unchanged requested hold', async () => {
    const request: VenueBookingDto = { bookingId: 'b1', status: 'pending',
      venue: { venueId: 'v1', venueAddress: 'Hall', venueCapacity: 100, supportedLayouts: [], venueAccessibilities: [],
        venueFacilities: [], operatingInformation: '', additionalInformation: null },
      event: { eventId: 'e1', eventName: 'Workshop', expectedAttendance: 50, accessibilityNeeds: [], venueRequirements: '', equipmentRequirements: null,
        startDatetime: '2027-04-01T10:00:00+08:00', endDatetime: '2027-04-01T12:00:00+08:00' } }
    vi.mocked(apiClient.post).mockRejectedValue(new ApiClientError('Maintenance blocks this hold.', 409, null, [], [{
      unavailabilityId: 'u1', reason: 'Repairs', startDatetime: '2027-04-01T10:00:00+08:00', endDatetime: '2027-04-01T12:00:00+08:00',
    }]))
    render(<VenueHoldPanel bookingId="b1" booking={request} pending initialHolds={[]} onChanged={vi.fn()} />)
    const input = screen.getByLabelText('Hold expiry (Singapore time)')
    fireEvent.change(input, { target: { value: '2099-03-01T10:00' } })
    fireEvent.submit(input.closest('form')!)
    expect(await screen.findByRole('group', { name: 'Unavailable period: Repairs' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Maintenance blocks this hold.')
    expect(screen.getByLabelText('Hold expiry (Singapore time)')).toHaveValue('2099-03-01T10:00')
  })
})
