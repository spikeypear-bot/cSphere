// @stories TS02 TS09 TS03
import { describe, expect, it } from 'vitest'
import { toIso } from './reservationApi'
import { toLocalInputValue } from './reservationUtils'

// Every time Technical Support types is Singapore time, whatever timezone the
// browser is set to (CI runs these in UTC). Before this, a date-time field was
// read as browser-local time, so a reported period could miss the event.
describe('TS02 TS09 — loan windows and reported periods are Singapore time', () => {
  it('TS09 (boundary): a typed 09:00 means 09:00 in Singapore, i.e. 01:00 UTC', () => {
    expect(toIso('2027-03-10T09:00')).toBe('2027-03-10T01:00:00.000Z')
  })

  it('TS02 (boundary): an instant shows as its Singapore wall-clock time, including across midnight', () => {
    expect(toLocalInputValue('2027-03-10T01:00:00Z')).toBe('2027-03-10T09:00')
    expect(toLocalInputValue('2027-03-10T17:30:00Z')).toBe('2027-03-11T01:30')
  })

  it('TS02 (happy): showing an instant and reading it back gives the same instant', () => {
    expect(toIso(toLocalInputValue('2027-03-10T04:00:00Z'))).toBe('2027-03-10T04:00:00.000Z')
  })

  it('TS02 (boundary): a value that already names its offset is kept as that instant', () => {
    expect(toIso('2027-03-10T09:00:00+08:00')).toBe('2027-03-10T01:00:00.000Z')
    expect(toIso('2027-03-10T01:00:00Z')).toBe('2027-03-10T01:00:00.000Z')
  })
})
