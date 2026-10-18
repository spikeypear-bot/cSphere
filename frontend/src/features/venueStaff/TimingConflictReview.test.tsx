// @stories VS-C4
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { TimingConflictReview } from './TimingConflictReview'
import { apiClient } from '../../lib/apiClient'
import type { ScheduleBooking } from './VenueUnavailabilityPanel'
import { BookingTimingSummary } from '../../components/BookingTimingSummary'
vi.mock('../../lib/apiClient', async original => ({ ...await original<typeof import('../../lib/apiClient')>(), apiClient: { get: vi.fn(), post: vi.fn() } }))
const first: ScheduleBooking = { bookingId: 'a', eventName: 'First event', venueName: 'Hall', status: 'approved',
  startDateTime: '2030-04-01T12:00:00+08:00', endDateTime: '2030-04-01T14:00:00+08:00',
  effectiveStart: '2030-04-01T12:00:00+08:00', effectiveEnd: '2030-04-01T15:00:00+08:00',
  eventStartDateTime: '2030-04-01T10:00:00+08:00', eventEndDateTime: '2030-04-01T17:00:00+08:00',
  setupDurationMinutes: 0, turnaroundDurationMinutes: 60 }
const second = { ...first, bookingId: 'b', eventName: 'Second event', startDateTime: '2030-04-01T15:00:00+08:00',
  endDateTime: '2030-04-01T17:00:00+08:00', effectiveStart: '2030-04-01T14:00:00+08:00', effectiveEnd: '2030-04-01T17:00:00+08:00', setupDurationMinutes: 60, turnaroundDurationMinutes: 0 }
afterEach(() => { cleanup(); vi.resetAllMocks() })
it('VS-C4 AC7, AC9 (conflict): shows every relationship, saved durations and booking links with read-only retrieval', async () => {
  vi.mocked(apiClient.get).mockResolvedValue([{ first, second }, { first, second: { ...second, bookingId: 'c', eventName: 'Third event' } }])
  render(<MemoryRouter><TimingConflictReview venueId="v" /></MemoryRouter>)
  const list = await screen.findByRole('region', { name: 'Booking Conflicts' })
  expect(within(list).getAllByRole('link', { name: 'First event' })).toHaveLength(2)
  expect(within(list).getByRole('link', { name: 'Third event' })).toHaveAttribute('href', '/venue-staff/bookings/c')
  expect(within(list).getAllByText('0 / 60 minutes')).toHaveLength(2)
  expect(within(list).getAllByText('60 / 0 minutes')).toHaveLength(2)
  expect(within(list).getAllByText('Advertised event time (SGT)')).toHaveLength(4)
  expect(within(list).getAllByText('Booking window (SGT)')).toHaveLength(4)
  expect(within(list).getAllByText('Conflict period (SGT):')).toHaveLength(2)
  expect(apiClient.get).toHaveBeenCalledWith('/venues/v/timing-conflicts', { cache: 'no-store' })
  expect(apiClient.post).not.toHaveBeenCalled()
})
it('VS-C4 AC10, AC11 (failure): removes stale conflicts and shows errors instead of an empty result; retry reads current data', async () => {
  vi.mocked(apiClient.get).mockResolvedValueOnce([{ first, second }]).mockRejectedValueOnce(new Error('Offline')).mockResolvedValueOnce([])
  render(<MemoryRouter><TimingConflictReview venueId="v" /></MemoryRouter>)
  await screen.findByRole('link', { name: 'First event' })
  fireEvent.click(screen.getByRole('button', { name: 'Refresh conflicts' }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('link', { name: 'First event' })).not.toBeInTheDocument()
  expect(screen.queryByText(/No booking conflicts/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry conflicts' }))
  await screen.findByText(/No booking conflicts detected/)
})
it('VS-C4 AC9 (happy): booking timing summary links the conflicting booking on the staff details route', () => {
  render(<MemoryRouter><BookingTimingSummary bookingLinks timing={{ startDatetime: first.startDateTime, endDatetime: first.endDateTime,
    effectiveStart: first.effectiveStart, effectiveEnd: first.effectiveEnd, setupDurationMinutes: 0, turnaroundDurationMinutes: 60,
    timingNote: null, conflicts: [{ bookingId: second.bookingId, eventName: second.eventName, startDatetime: second.startDateTime,
      endDatetime: second.endDateTime, effectiveStart: second.effectiveStart, effectiveEnd: second.effectiveEnd }] }} /></MemoryRouter>)
  expect(screen.getByRole('link', { name: 'Second event' })).toHaveAttribute('href', '/venue-staff/bookings/b')
})
it('VS-C4 AC9 (scope): conflict links remain opt-in for shared summaries outside Venue Staff', () => {
  render(<BookingTimingSummary timing={{ startDatetime: first.startDateTime, endDatetime: first.endDateTime,
    effectiveStart: first.effectiveStart, effectiveEnd: first.effectiveEnd, setupDurationMinutes: 0, turnaroundDurationMinutes: 60,
    timingNote: null, conflicts: [{ bookingId: second.bookingId, eventName: second.eventName, startDatetime: second.startDateTime,
      endDatetime: second.endDateTime, effectiveStart: second.effectiveStart, effectiveEnd: second.effectiveEnd }] }} />)
  expect(screen.getByText(/Second event/)).toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})
