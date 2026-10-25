// Date windows for the console's inventory reads (D32: half-open [from, to)).
// Dates are picked as whole local days and the last day counts, so the
// window ends at midnight after it.

const pad = (n: number) => String(n).padStart(2, '0')

/** A Date as the value of <input type="date">, e.g. "2026-10-25". */
export function toDateInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** "2026-10-25" -> that day's local midnight, or null when not a date. */
function startOfDay(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

export function addDays(value: string, days: number): string {
  const date = startOfDay(value) ?? new Date()
  date.setDate(date.getDate() + days)
  return toDateInput(date)
}

export interface DateRange {
  /** First day, as <input type="date"> gives it. */
  from: string
  /** Last day (inclusive). */
  to: string
}

/** Today and the next `days` days. */
export function rangeFromToday(days: number): DateRange {
  const today = toDateInput(new Date())
  return { from: today, to: addDays(today, days) }
}

/** The instants to send for a range, or null when the range is invalid
 * (missing day, or the last day before the first). */
export function rangeToWindow(range: DateRange): { from: string; to: string } | null {
  const start = startOfDay(range.from)
  const lastDay = startOfDay(range.to)
  if (!start || !lastDay || lastDay < start) return null
  const end = new Date(lastDay)
  end.setDate(end.getDate() + 1)
  return { from: start.toISOString(), to: end.toISOString() }
}
