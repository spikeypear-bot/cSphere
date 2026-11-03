// @stories TS09
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addDays, rangeFromParams, rangeToWindow } from './dateWindow'

describe('TS09 inventory date windows', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2027, 2, 10, 15, 30))
  })
  afterEach(() => vi.useRealTimers())

  it('TS09 (negative): a missing or malformed day gives no window to read', () => {
    expect(rangeToWindow({ from: '', to: '2027-03-12' })).toBeNull()
    expect(rangeToWindow({ from: '2027-03-10', to: '12/03/2027' })).toBeNull()
  })

  it('TS09 (boundary): days are counted from today when the starting day is not a date', () => {
    expect(addDays('not a date', 7)).toBe('2027-03-17')
    expect(rangeFromParams(new URLSearchParams(''))).toEqual({ from: '2027-03-10', to: '2027-03-17' })
  })
})
