import { expect, it } from 'vitest'
import { singaporeToday, unavailabilityDateErrors } from './unavailabilityValidation'

const now = new Date('2026-10-07T10:30:00+08:00')
it.each([
  [1, '2026-10-08T10:00', '2026-10-09T11:00', true],
  [2, '', '2026-10-09T11:00', false],
  [3, '2026-10-08T10:00', '', false],
  [4, '', '', false],
  [5, '2026-10-08T10:00', '2026-10-08T10:00', false],
  [6, '2026-10-09T10:00', '2026-10-08T11:00', false],
  [7, '2026-10-08T10:00', '2026-10-08T11:00', true],
  [8, '2026-10-08T10:00', '2026-10-08T09:00', false],
  [9, '2026-10-08T10:00', '2026-10-08T10:00', false],
  [10, '2026-10-07T10:30', '2026-10-07T11:00', true],
  [11, '2026-10-06T10:00', '2026-10-08T11:00', false],
  [12, '2026-10-05T10:00', '2026-10-06T11:00', false],
  [13, '2026-10-08T10:00', '2026-10-09T11:00', true],
  [14, '2026-10-31T23:00', '2026-11-01T01:00', true],
  [15, '2026-12-31T23:00', '2027-01-01T01:00', true],
  [16, '2027-02-28T23:00', '2027-03-01T01:00', true],
  [17, '2028-02-29T10:00', '2028-03-01T11:00', true],
  [17, '2028-02-28T10:00', '2028-02-29T11:00', true],
  [18, '2027-02-29T10:00', '2027-03-01T11:00', false],
  [18, '2027-02-28T10:00', '2027-02-29T11:00', false],
  [19, '2027-04-31T10:00', '2027-05-01T11:00', false],
  [20, '2027-04-30T10:00', '2027-04-31T11:00', false],
] as const)('scenario %s: %s → %s is accepted=%s', (_id, start, end, accepted) => {
  expect(Object.keys(unavailabilityDateErrors(start, end, now)).length === 0).toBe(accepted)
})
it('uses Singapore midnight and the Gregorian century leap-year rule', () => {
  expect(singaporeToday(new Date('2026-10-06T16:00:00Z'))).toBe('2026-10-07')
  expect(unavailabilityDateErrors('2026-10-06T23:59', '2026-10-07T01:00', new Date('2026-10-06T16:00:00Z')).start).toMatch(/before today/)
  expect(unavailabilityDateErrors('2100-02-29T10:00', '2100-03-01T10:00', now).start).toMatch(/valid start/)
  expect(unavailabilityDateErrors('2400-02-29T10:00', '2400-03-01T10:00', now)).toEqual({})
})
