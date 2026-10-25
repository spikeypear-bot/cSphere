import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'

/** A block or report period; no end means until further notice. */
export function describeSpan(start: string, end: string | null): string {
  return end === null
    ? `From ${formatEventDateTime(start)}, until further notice`
    : `${formatEventDateTime(start)} – ${formatEventDateTime(end)}`
}

/** The requester line: a username, or that nobody is assigned. */
export function requesterName(requestedBy: { name: string } | null | undefined): string {
  return requestedBy?.name ?? 'No coordinator assigned'
}
