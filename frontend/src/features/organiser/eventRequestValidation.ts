/** Compare full instants: equal timestamps and past dates are permitted. */
export function dateRangeError(start: string | null, end: string | null): string | undefined {
  if (!start || !end) return undefined // Incomplete drafts remain saveable.
  const first = Date.parse(start)
  const last = Date.parse(end)
  if (!Number.isFinite(first) || !Number.isFinite(last)) return 'Enter valid start and end dates and times.'
  return last < first ? 'End date & time must be on or after start date & time. Update the end or start date.' : undefined
}
