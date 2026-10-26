// @stories VS17 VS15 VS-C3
import { describe, expect, it } from 'vitest'
import { calendarEntries } from './venueCalendar'
import type { VenueSchedule } from './VenueUnavailabilityPanel'

describe('VS17 calendar operational restrictions', () => {
  const at = (hour: string) => `2031-04-01T${hour}:00+08:00`
  it('VS17 (boundary): shows timed issues, setup-only conflicts and hold conflicts using half-open intervals', () => {
    const data: VenueSchedule = { settings: { setupMinutes: 30, turnaroundMinutes: 0 }, unavailablePeriods: [],
      operationalIssues: [{ issueId: 'issue', description: 'Loading bay closed', affectedFrom: at('09:00'), affectedUntil: at('10:00') },
        { issueId: 'information', description: 'Information', affectedFrom: '', affectedUntil: '' }],
      bookings: [{ bookingId: 'b1', eventName: 'Conference', status: 'approved', startDateTime: at('10:00'), endDateTime: at('12:00'), effectiveStart: at('09:30'), effectiveEnd: at('12:00') }],
      holds: [{ holdId: 'h1', bookingId: 'b2', eventId: 'e2', venueId: 'v1', eventName: 'Held event', status: 'active',
        reservedStart: at('09:30'), reservedEnd: at('11:00'), expiresAt: at('08:00'), serverTime: at('07:00'), createdBy: 'vs1', createdAt: at('07:00'), endedAt: null }] }
    const entries = calendarEntries(data)
    expect(entries.filter(e => e.type === 'issue')).toHaveLength(1)
    expect(entries.find(e => e.phase === 'Setup')?.conflictReasons).toEqual(['Blocking operational issue: Loading bay closed'])
    expect(entries.find(e => e.phase === 'Event')?.conflictReasons).toEqual([])
    expect(entries.find(e => e.type === 'tentative')?.conflictReasons).toEqual(['Blocking operational issue: Loading bay closed'])
    expect(calendarEntries({ ...data, operationalIssues: [] }).every(e => !e.conflictReasons?.length)).toBe(true)
    expect(calendarEntries(data, new Set(['h1'])).some(e => e.type === 'tentative')).toBe(false)
  })
})
