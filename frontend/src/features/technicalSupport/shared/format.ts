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

// The front page's short forms. Event times are shown in Singapore time
// everywhere (formatEventDateTime), so "today" is a Singapore day too.
const ZONE = 'Asia/Singapore'
const dayKeyFormat = new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
const hourFormat = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, hour: 'numeric', hourCycle: 'h23' })
const timeFormat = new Intl.DateTimeFormat('en-SG', { timeZone: ZONE, hour: 'numeric', minute: '2-digit', hour12: true })
const dayFormat = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, weekday: 'short', day: 'numeric', month: 'short' })
const dayYearFormat = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })

/** Whole days since the epoch for the Singapore calendar day of `date`. */
function dayNumber(date: Date): number {
  const [year, month, day] = dayKeyFormat.format(date).split('-').map(Number)
  return Date.UTC(year, month - 1, day) / 86_400_000
}

const yearOf = (date: Date) => dayKeyFormat.format(date).slice(0, 4)

/** "Tue 27 Oct", with the year only when it is not this year's. */
export function shortDate(value: string, now = new Date()): string {
  const date = new Date(value)
  return (yearOf(date) === yearOf(now) ? dayFormat : dayYearFormat).format(date)
}

/** "Today", "Tomorrow", or the short date. */
export function relativeDay(value: string, now = new Date()): string {
  const diff = dayNumber(new Date(value)) - dayNumber(now)
  if (diff === 0) return 'Today'
  if (diff === 1) return 'Tomorrow'
  return shortDate(value, now)
}

/** "Today, 9:00 am – 12:00 pm"; an event that ends on a later day names
 * that day too ("Today, 6:00 pm – Tomorrow, 1:00 am"). */
export function describeWhen(start: string, end: string, now = new Date()): string {
  const sameDay = dayNumber(new Date(start)) === dayNumber(new Date(end))
  const startText = `${relativeDay(start, now)}, ${timeFormat.format(new Date(start))}`
  const endText = sameDay ? timeFormat.format(new Date(end)) : `${relativeDay(end, now)}, ${timeFormat.format(new Date(end))}`
  return `${startText} – ${endText}`
}

/** "Until Sat 20 Mar", or "No end date" for a block until further notice. */
export function describeUntil(end: string | null, now = new Date()): string {
  return end === null ? 'No end date' : `Until ${shortDate(end, now)}`
}

/** "Good morning" before noon, "Good afternoon" before 6 pm, else evening. */
export function greetingFor(now = new Date()): string {
  const hour = Number(hourFormat.format(now))
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** "1 request", "2 requests". */
export function countOf(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

/** The first three lines of a request ("2 × Projector, 1 × Webcam") and
 * "+N more" for the rest. */
export function previewItems(items: { equipmentName: string; quantity: number }[] | undefined, shown = 3): string {
  if (!items || items.length === 0) return 'No itemised equipment'
  const listed = items.slice(0, shown).map((item) => `${item.quantity} × ${item.equipmentName}`).join(', ')
  return items.length > shown ? `${listed} +${items.length - shown} more` : listed
}
