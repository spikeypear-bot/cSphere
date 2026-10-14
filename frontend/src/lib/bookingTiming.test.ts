import { expect, it } from 'vitest'
import { occupiedPeriod, validMinutes } from './bookingTiming'
import { approvedOverlaps } from '../features/venueStaff/venueCalendar'
import type { VenueSchedule } from '../features/venueStaff/VenueUnavailabilityPanel'

it('validates minutes and calculates across midnight using timestamp instants', () => {
  for (const value of [null, undefined, '', -1, 0.5, 10081, 'bad']) expect(validMinutes(value)).toBe(false)
  for (const value of [0, 30, 10080, '45']) expect(validMinutes(value)).toBe(true)
  expect(occupiedPeriod('2030-01-01T00:15:00+08:00', '2030-01-01T01:00:00+08:00', 30, 15))
    .toEqual({ effectiveStart: '2029-12-31T15:45:00.000Z', effectiveEnd: '2029-12-31T17:15:00.000Z' })
})
it('identifies approved snapshot overlaps, allowing touching boundaries and ignoring pending holds', () => {
  const first = { bookingId: 'a', eventName: 'A', status: 'approved', startDateTime: '2030-01-01T10:00:00Z', endDateTime: '2030-01-01T11:00:00Z',
    effectiveStart: '2030-01-01T09:30:00Z', effectiveEnd: '2030-01-01T11:30:00Z' }
  const second = { ...first, bookingId: 'b', eventName: 'B', effectiveStart: '2030-01-01T11:30:00Z', effectiveEnd: '2030-01-01T13:00:00Z' }
  const schedule: VenueSchedule = { settings: { setupMinutes: 120, turnaroundMinutes: 120 }, unavailablePeriods: [], bookings: [first, second] }
  expect(approvedOverlaps(schedule)).toEqual([])
  second.effectiveStart = '2030-01-01T11:29:00Z'
  expect(approvedOverlaps(schedule)).toHaveLength(1)
  second.status = 'pending'
  expect(approvedOverlaps(schedule)).toEqual([])
})
