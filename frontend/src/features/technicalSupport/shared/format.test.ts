// @stories TS09  (IS212 traceability: the user stories this file tests; see docs/test-traceability.md)
import { describe, expect, it } from 'vitest'
import { countOf, describeUntil, describeWhen, greetingFor, previewItems, relativeDay } from './format'

// Wednesday 10 March 2027, 3:30 pm in Singapore.
const NOW = new Date('2027-03-10T07:30:00Z')
const sg = (local: string) => new Date(`${local}+08:00`)

describe('TS09 front page wording', () => {
  it('TS09 (boundary): the greeting changes at noon and at 6 pm Singapore time', () => {
    expect(greetingFor(sg('2027-03-10T00:00:00'))).toBe('Good morning')
    expect(greetingFor(sg('2027-03-10T11:59:00'))).toBe('Good morning')
    expect(greetingFor(sg('2027-03-10T12:00:00'))).toBe('Good afternoon')
    expect(greetingFor(sg('2027-03-10T17:59:00'))).toBe('Good afternoon')
    expect(greetingFor(sg('2027-03-10T18:00:00'))).toBe('Good evening')
    expect(greetingFor(sg('2027-03-10T23:59:00'))).toBe('Good evening')
  })

  it('TS09 (boundary): days are Today and Tomorrow by the Singapore calendar, then a short date, with the year only when it differs', () => {
    expect(relativeDay('2027-03-10T15:59:00Z', NOW)).toBe('Today') // 11:59 pm in Singapore
    expect(relativeDay('2027-03-10T16:00:00Z', NOW)).toBe('Tomorrow') // midnight in Singapore
    expect(relativeDay('2027-03-11T16:00:00Z', NOW)).toBe('Fri 12 Mar')
    expect(relativeDay('2027-03-09T01:00:00Z', NOW)).toBe('Tue 9 Mar')
    expect(relativeDay('2028-01-04T01:00:00Z', NOW)).toBe('Tue, 4 Jan 2028')
  })

  it('TS09 (happy): an event time is its day and time range, naming the end day only when it differs', () => {
    expect(describeWhen('2027-03-11T01:00:00Z', '2027-03-11T04:30:00Z', NOW)).toBe('Tomorrow, 9:00 am – 12:30 pm')
    expect(describeWhen('2027-03-10T10:00:00Z', '2027-03-10T17:00:00Z', NOW)).toBe('Today, 6:00 pm – Tomorrow, 1:00 am')
  })

  it('TS09 (happy): items show the first three and how many more, and counts use the singular for one', () => {
    const line = (equipmentName: string, quantity: number) => ({ equipmentName, quantity })
    expect(previewItems([line('Projector', 2)])).toBe('2 × Projector')
    expect(previewItems([line('A', 1), line('B', 2), line('C', 3)])).toBe('1 × A, 2 × B, 3 × C')
    expect(previewItems([line('A', 1), line('B', 2), line('C', 3), line('D', 4)])).toBe('1 × A, 2 × B, 3 × C +1 more')
    expect(previewItems([])).toBe('No itemised equipment')
    expect(previewItems(undefined)).toBe('No itemised equipment')
    expect(countOf(0, 'item')).toBe('0 items')
    expect(countOf(1, 'item')).toBe('1 item')
    expect(countOf(2, 'request')).toBe('2 requests')
  })

  it('TS09 (happy): equipment out of action says until when, or that it has no end date', () => {
    expect(describeUntil('2027-03-20T00:00:00Z', NOW)).toBe('Until Sat 20 Mar')
    expect(describeUntil(null, NOW)).toBe('No end date')
  })
})
