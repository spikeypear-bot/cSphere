import { expect, it } from 'vitest'
import { dateRangeError } from './eventRequestValidation'

it('compares full instants and permits equal timestamps and past dates', () => {
  expect(dateRangeError('2020-09-22T09:00:00Z', '2020-09-23T09:00:00Z')).toBeUndefined()
  expect(dateRangeError('2020-09-22T09:00:00Z', '2020-09-22T09:00:00Z')).toBeUndefined()
  expect(dateRangeError('2020-09-22T09:00:00Z', '2020-09-22T17:00:00+08:00')).toBeUndefined()
  expect(dateRangeError('2020-09-22T09:00:00Z', '2020-09-22T08:59:00Z')).toBeDefined()
  expect(dateRangeError(null, '2020-09-22T09:00:00Z')).toBeUndefined()
  expect(dateRangeError('invalid', '2020-09-22T09:00:00Z')).toBeDefined()
})
