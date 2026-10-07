import type { VenueSchedule } from './VenueUnavailabilityPanel'

export interface CalendarEntry {
  id: string
  entryId: string
  type: 'confirmed' | 'tentative' | 'unavailable'
  phase: 'Event' | 'Setup' | 'Turnaround' | 'Unavailable'
  title: string
  start: string
  end: string
  eventStart?: string
  eventEnd?: string
}
export const entryLabels = { confirmed: 'Confirmed booking', tentative: 'Tentative hold', unavailable: 'Unavailable' }
export function calendarEntries(schedule: VenueSchedule): CalendarEntry[] {
  const entries: CalendarEntry[] = schedule.unavailablePeriods.map(p => ({
    id: `unavailable:${p.unavailabilityId}`, entryId: p.unavailabilityId, type: 'unavailable',
    phase: 'Unavailable', title: p.reason, start: p.startDateTime, end: p.endDateTime,
  }))
  for (const b of schedule.bookings) {
    if (b.status !== 'approved' && b.status !== 'pending') continue
    const base = { entryId: b.bookingId, type: b.status === 'approved' ? 'confirmed' as const : 'tentative' as const,
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
