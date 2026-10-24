// @stories EO02 EO-C2  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { describe, expect, it } from 'vitest'
import {
  ATTENDANCE_AT_LEAST_ONE, ATTENDANCE_WHOLE_NUMBER, END_AFTER_START, START_IN_FUTURE,
  dateRangeError, requestFieldProblems,
} from './eventRequestValidation'

/** 23 Oct 2026, 10:00 in Singapore: the moment "now" in these tests. */
const NOW = Date.parse('2026-10-23T02:00:00Z')
const valid = { startDatetime: '2026-10-30T02:00:00Z', endDatetime: '2026-10-30T04:00:00Z', expectedAttendance: 150 }

describe('EO-C2 request date, time and attendance rules (same as the server)', () => {
  it('EO-C2 AC1 (boundary): the end must be strictly after the start, compared as moments', () => {
    expect(dateRangeError('2026-10-30T09:00:00Z', '2026-10-30T09:01:00Z')).toBeUndefined()
    expect(dateRangeError('2026-10-30T09:00:00Z', '2026-10-30T09:00:00Z')).toBe(END_AFTER_START)
    // 09:00 UTC and 17:00 in Singapore are the same moment: no duration.
    expect(dateRangeError('2026-10-30T09:00:00Z', '2026-10-30T17:00:00+08:00')).toBe(END_AFTER_START)
    expect(dateRangeError('2026-10-30T09:00:00Z', '2026-10-30T08:59:00Z')).toBe(END_AFTER_START)
  })

  it('EO-C2 AC1 (happy): an overnight event ending on the next date is valid', () => {
    expect(requestFieldProblems({ ...valid, startDatetime: '2026-10-30T14:00:00Z', endDatetime: '2026-10-30T18:00:00Z' }, NOW))
      .toEqual({})
  })

  it('EO-C2 AC2 (boundary): a start at this very moment, or one minute ago, is refused; one minute from now is fine', () => {
    const at = (offsetMinutes: number) => new Date(NOW + offsetMinutes * 60_000).toISOString()
    expect(requestFieldProblems({ ...valid, startDatetime: at(0), endDatetime: at(60) }, NOW))
      .toEqual({ startDatetime: START_IN_FUTURE })
    expect(requestFieldProblems({ ...valid, startDatetime: at(-1), endDatetime: at(60) }, NOW).startDatetime)
      .toBe(START_IN_FUTURE)
    expect(requestFieldProblems({ ...valid, startDatetime: at(1), endDatetime: at(60) }, NOW)).toEqual({})
  })

  it('EO-C2 AC4 (boundary): attendance must be a whole number of at least 1', () => {
    expect(requestFieldProblems({ ...valid, expectedAttendance: 1 }, NOW)).toEqual({})
    expect(requestFieldProblems({ ...valid, expectedAttendance: 0 }, NOW)).toEqual({ expectedAttendance: ATTENDANCE_AT_LEAST_ONE })
    expect(requestFieldProblems({ ...valid, expectedAttendance: -5 }, NOW)).toEqual({ expectedAttendance: ATTENDANCE_AT_LEAST_ONE })
    expect(requestFieldProblems({ ...valid, expectedAttendance: 150.5 }, NOW)).toEqual({ expectedAttendance: ATTENDANCE_WHOLE_NUMBER })
  })

  it('EO-C2 AC6 (negative): every problem is reported at once, each against its field', () => {
    expect(requestFieldProblems({ startDatetime: '2025-01-01T02:00:00Z', endDatetime: '2025-01-01T01:00:00Z', expectedAttendance: 0 }, NOW))
      .toEqual({ startDatetime: START_IN_FUTURE, endDatetime: END_AFTER_START, expectedAttendance: ATTENDANCE_AT_LEAST_ONE })
  })

  it('EO-C2 AC7 (boundary): empty fields are not problems here (they are "missing", EO02), and drafts stay saveable', () => {
    expect(requestFieldProblems({ startDatetime: null, endDatetime: null, expectedAttendance: null }, NOW)).toEqual({})
    expect(dateRangeError(null, '2026-10-30T09:00:00Z')).toBeUndefined()
    expect(dateRangeError('invalid', '2026-10-30T09:00:00Z')).toBe('Enter valid start and end dates and times.')
  })
})
