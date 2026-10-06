/** Venue Staff dates are Singapore wall-clock times, regardless of browser timezone. */
export function singaporeToday(now = new Date()): string {
  return new Date(now.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

function validCalendarTime(value: string): boolean {
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!parts) return false
  const [, y, m, d, h, min, sec = '0'] = parts
  const year = Number(y), month = Number(m), day = Number(d)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]
    && Number(h) < 24 && Number(min) < 60 && Number(sec) < 60
}

export function unavailabilityDateErrors(start: string, end: string, now = new Date()): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!start) errors.start = 'Start date/time is required.'
  else if (!validCalendarTime(start)) errors.start = 'Enter a valid start calendar date and time.'
  else if (start.slice(0, 10) < singaporeToday(now)) errors.start = 'Start date cannot be before today (Singapore time).'
  if (!end) errors.end = 'End date/time is required.'
  else if (!validCalendarTime(end)) errors.end = 'Enter a valid end calendar date and time.'
  if (!errors.start && !errors.end && Date.parse(`${end}+08:00`) <= Date.parse(`${start}+08:00`)) {
    errors.start = errors.end = 'End date/time must be strictly later than start.'
  }
  return errors
}
