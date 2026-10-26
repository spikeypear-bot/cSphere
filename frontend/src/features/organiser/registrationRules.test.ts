// @stories AT01 AT02
import { describe, expect, it } from 'vitest'
import { getMissingRequiredFields } from './eventRequestCompletion'
import { requestFieldProblems } from './eventRequestValidation'
import { detailsOf, differences } from '../changeRequests/changeRequestFields'
import type { DraftFields } from './useEventRequestDraft'
import type { EventRequestDto } from '../../types/eventRequest'

const fields: DraftFields = {
  eventName: 'Workshop', purpose: 'Learning', description: null,
  startDatetime: '2027-03-01T10:00:00Z', endDatetime: '2027-03-01T12:00:00Z',
  expectedAttendance: 10, venueRequirements: 'Room', equipmentRequirements: null,
  accessibilityNeeds: ['none'], registrationNeeds: true,
  registrationCapacity: 10, registrationOpensAt: '2027-02-01T10:00:00Z',
}

describe('AT01 AT02 registration rules follow event start', () => {
  it('AT01 (happy): a complete registration request needs no separate closing time', () => {
    expect(getMissingRequiredFields(fields)).toEqual([])
    expect(requestFieldProblems(fields, Date.parse('2027-01-01T00:00:00Z'))).toEqual({})
  })

  it('AT01 (boundary): registration must open strictly before event start', () => {
    expect(requestFieldProblems({ ...fields, registrationOpensAt: fields.startDatetime }, 0))
      .toEqual({ registrationOpensAt: 'Registration must open before the event start.' })
  })

  it('AT01 (cross-cutting): change requests preserve registration settings and identify changes', () => {
    const details = detailsOf(fields as EventRequestDto)
    expect(details.registrationCapacity).toBe(10)
    expect(details.registrationOpensAt).toBe(fields.registrationOpensAt)
    expect(differences(details, { ...details, registrationCapacity: 12 }).map(c => c.field))
      .toEqual(['registrationCapacity'])
  })
})
