// @stories VS-C3 VS03 VS15 VS17
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
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
