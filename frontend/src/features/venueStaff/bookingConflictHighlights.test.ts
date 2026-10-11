import { expect, it } from 'vitest'
import { bookingConflictHighlights } from './bookingConflictHighlights'

const period = (start: string, end: string) => ({ startDatetime: `2027-10-16T${start}:00+08:00`, endDatetime: `2027-10-16T${end}:00+08:00` })

it.each([
  ['09:00', '12:00', '11:30', '14:00', [false, true, true, false]],
  ['11:30', '14:00', '09:00', '12:00', [true, false, false, true]],
  ['09:00', '14:00', '10:00', '12:00', [true, true, true, true]],
  ['10:00', '12:00', '09:00', '14:00', [true, true, true, true]],
  ['09:00', '12:00', '09:00', '12:00', [true, true, true, true]],
  ['09:00', '12:00', '12:00', '14:00', [false, false, false, false]],
  ['12:00', '14:00', '09:00', '12:00', [false, false, false, false]],
  ['09:00', '12:00', '13:00', '14:00', [false, false, false, false]],
] as const)('highlights request %s–%s against booking %s–%s', (start, end, otherStart, otherEnd, flags) => {
  expect(bookingConflictHighlights(period(start, end), period(otherStart, otherEnd))).toEqual({
    requestStart: flags[0], requestEnd: flags[1], existingStart: flags[2], existingEnd: flags[3],
  })
})

it('compares instants across midnight and timezone offsets', () => {
  expect(bookingConflictHighlights(
    { startDatetime: '2027-10-16T23:00:00+08:00', endDatetime: '2027-10-17T02:00:00+08:00' },
    { startDatetime: '2027-10-16T17:00:00Z', endDatetime: '2027-10-16T20:00:00Z' },
  )).toEqual({ requestStart: false, requestEnd: true, existingStart: true, existingEnd: false })
})
