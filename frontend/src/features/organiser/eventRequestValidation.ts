// EO-C2: the same date, time and attendance rules as the server
// (EventRequestService.scheduleProblems), checked as the organiser types so
// each problem shows next to its field straight away. The server still
// decides on submit; these messages match its own, word for word.

export const START_IN_FUTURE = 'The start date & time must be in the future.'
export const END_AFTER_START = 'The end date & time must be after the start date & time.'
export const ATTENDANCE_AT_LEAST_ONE = 'Expected attendance must be at least 1.'
export const ATTENDANCE_WHOLE_NUMBER = 'Expected attendance must be a whole number.'
const INVALID_DATES = 'Enter valid start and end dates and times.'

export type RequestRuleField = 'startDatetime' | 'endDatetime' | 'expectedAttendance'
export type RequestFieldProblems = Partial<Record<RequestRuleField, string>>

/** The end must be strictly after the start, compared as moments: the same
 * moment written with another offset is the same moment, so no duration. */
export function dateRangeError(start: string | null, end: string | null): string | undefined {
  if (!start || !end) return undefined // Incomplete drafts remain saveable.
  const first = Date.parse(start)
  const last = Date.parse(end)
  if (!Number.isFinite(first) || !Number.isFinite(last)) return INVALID_DATES
  return last <= first ? END_AFTER_START : undefined
}

/**
 * Every reason the request could not be sent now, keyed by field, in the
 * form's order. Empty fields are not problems here: they are "missing"
 * (EO02's checklist), reported once. `now` is a parameter so the boundary
 * (a start at this very moment has already started) is testable.
 */
export function requestFieldProblems(
  fields: { startDatetime: string | null; endDatetime: string | null; expectedAttendance: number | null },
  now: number = Date.now(),
): RequestFieldProblems {
  const problems: RequestFieldProblems = {}
  if (fields.startDatetime) {
    const start = Date.parse(fields.startDatetime)
    if (Number.isFinite(start) && start <= now) problems.startDatetime = START_IN_FUTURE
  }
  const range = dateRangeError(fields.startDatetime, fields.endDatetime)
  if (range) problems.endDatetime = range
  const attendance = fields.expectedAttendance
  if (attendance !== null && attendance !== undefined) {
    if (!Number.isInteger(attendance)) problems.expectedAttendance = ATTENDANCE_WHOLE_NUMBER
    else if (attendance < 1) problems.expectedAttendance = ATTENDANCE_AT_LEAST_ONE
  }
  return problems
}

export function hasProblems(problems: RequestFieldProblems): boolean {
  return Object.keys(problems).length > 0
}
