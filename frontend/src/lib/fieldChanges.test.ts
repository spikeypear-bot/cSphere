import { describe, expect, it } from 'vitest'
import { changedFields, formatFieldValue } from './fieldChanges'

describe('changedFields', () => {
  it('reports only real changes, in form order, readably', () => {
    const changes = changedFields(
      { eventName: 'Gala', expectedAttendance: 150, accessibilityNeeds: ['elevators', 'none'], registrationNeeds: false },
      { eventName: 'Gala', expectedAttendance: 120, accessibilityNeeds: ['none', 'elevators'], registrationNeeds: true },
    )
    expect(changes.map((c) => [c.label, c.before, c.after])).toEqual([
      ['Expected attendance', '150', '120'],
      ['Registration', 'No', 'Yes'],
    ])
  })

  it('treats the same instant written with a different offset as unchanged', () => {
    expect(changedFields({ startDatetime: '2027-03-10T01:00:00Z' },
      { startDatetime: '2027-03-10T09:00:00+08:00' })).toEqual([])
  })

  it('treats empty text, null and an empty list as the same', () => {
    expect(changedFields({ description: '', accessibilityNeeds: [] }, { description: null })).toEqual([])
  })

  it('returns nothing when either moment is unknown (older timeline entries)', () => {
    expect(changedFields(null, { purpose: 'x' })).toEqual([])
  })

  it('formats blanks as a dash', () => {
    expect(formatFieldValue('purpose', null)).toBe('—')
  })
})
