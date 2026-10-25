import { BOOKING_STATUS_LABELS, type VenueBookingDisplayStatus } from '../../types/venueBookingStatus'
import type { VenueSchedule } from './VenueUnavailabilityPanel'

export function approvedOverlaps(schedule: VenueSchedule) {
  const bookings = schedule.bookings.filter(b => b.status === 'approved' || b.status === 'attention')
  return bookings.flatMap((first, index) => bookings.slice(index + 1)
    .filter(second => Date.parse(first.effectiveStart) < Date.parse(second.effectiveEnd)
      && Date.parse(first.effectiveEnd) > Date.parse(second.effectiveStart))
    .map(second => ({ first, second })))
}

export interface CalendarEntry {
  id: string
  entryId: string
  type: Extract<VenueBookingDisplayStatus, 'approved' | 'attention' | 'tentative' | 'pending'> | 'unavailable'
  phase: 'Event' | 'Setup' | 'Turnaround' | 'Unavailable' | 'Reserved period'
  title: string
  start: string
  end: string
  expiresAt?: string
  eventStart?: string
  eventEnd?: string
}
export const entryLabels = { attention: BOOKING_STATUS_LABELS.attention, approved: `${BOOKING_STATUS_LABELS.approved} booking`, tentative: BOOKING_STATUS_LABELS.tentative, pending: `${BOOKING_STATUS_LABELS.pending} request`, unavailable: 'Unavailable' }
export function calendarEntries(schedule: VenueSchedule, expired = new Set<string>()): CalendarEntry[] {
  const entries: CalendarEntry[] = schedule.unavailablePeriods.map(p => ({
    id: `unavailable:${p.unavailabilityId}`, entryId: p.unavailabilityId, type: 'unavailable',
    phase: 'Unavailable', title: p.reason, start: p.startDateTime, end: p.endDateTime,
  }))
  const activeHolds = (schedule.holds ?? []).filter(h => h.status === 'active' && !expired.has(h.holdId))
  for (const h of activeHolds) entries.push({ id: 'hold:' + h.holdId, entryId: h.bookingId, type: 'tentative',
    phase: 'Reserved period', title: h.eventName, start: h.reservedStart, end: h.reservedEnd, expiresAt: h.expiresAt })
  for (const b of schedule.bookings) {
    if (activeHolds.some(h => h.bookingId === b.bookingId)) continue
    if (b.status !== 'approved' && b.status !== 'attention' && b.status !== 'pending') continue
    const base = { entryId: b.bookingId, type: b.status as 'approved' | 'attention' | 'pending',
      title: b.eventName, eventStart: b.startDateTime, eventEnd: b.endDateTime }
    for (const [phase, start, end] of [
      ['Setup', b.effectiveStart, b.startDateTime], ['Event', b.startDateTime, b.endDateTime],
      ['Turnaround', b.endDateTime, b.effectiveEnd],
    ] as const) {
      if (Date.parse(end) > Date.parse(start)) entries.push({ ...base, id: `booking:${b.bookingId}:${phase}`, phase, start, end })
    }
  }
  return entries.sort((a, b) => Date.parse(a.start) - Date.parse(b.start) || a.id.localeCompare(b.id))
}
export function singaporeDate(date = new Date()) {
  return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 10)
}
export function shiftMonth(month: string, offset: number) {
  const [year, number] = month.split('-').map(Number)
  return new Date(Date.UTC(year, number - 1 + offset, 1)).toISOString().slice(0, 7)
}
export function calendarDays(month: string) {
  const first = new Date(`${month}-01T00:00:00Z`)
  const last = new Date(`${shiftMonth(month, 1)}-01T00:00:00Z`)
  const count = Math.ceil((first.getUTCDay() + (last.getTime() - first.getTime()) / 86400000) / 7) * 7
  return Array.from({ length: count }, (_, i) => new Date(first.getTime() + (i - first.getUTCDay()) * 86400000).toISOString().slice(0, 10))
}
export const midnight = (day: string) => `${day}T00:00:00+08:00`
export function entriesOnDay(entries: CalendarEntry[], day: string) {
  const start = Date.parse(midnight(day))
  return entries.filter(e => Date.parse(e.start) < start + 86400000 && Date.parse(e.end) > start)
}
export function dayTime(entry: CalendarEntry, day: string) {
  const time = (value: string) => new Intl.DateTimeFormat('en-SG', { timeZone: 'Asia/Singapore', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
  const start = Date.parse(midnight(day))
  const end = Date.parse(entry.end)
  return `${Date.parse(entry.start) < start ? '←' : time(entry.start)} – ${end > start + 86400000 ? '→' : end === start + 86400000 ? '24:00' : time(entry.end)}`
}
