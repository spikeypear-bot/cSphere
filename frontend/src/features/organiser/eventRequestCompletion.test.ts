import { describe, expect, it } from 'vitest'
import { getMissingRequiredFields, getCompletionPercent } from './eventRequestCompletion'
import type { DraftFields } from './useEventRequestDraft'

const BLANK: DraftFields = {
  eventName: null,
  purpose: null,
  description: null,
  startDatetime: null,
  endDatetime: null,
  expectedAttendance: null,
  venueRequirements: null,
  equipmentRequirements: null,
  accessibilityNeeds: [],
  registrationNeeds: null,
}

const COMPLETE: DraftFields = {
  ...BLANK,
  eventName: 'Q1 Town Hall',
  purpose: 'All-hands update',
  startDatetime: '2026-12-01T09:00:00Z',
  endDatetime: '2026-12-01T11:00:00Z',
  expectedAttendance: 150,
  venueRequirements: 'Theatre-style seating for 150',
  accessibilityNeeds: ['none'],
}

describe('eventRequestCompletion — mirrors EventRequestService.missingRequiredFields()', () => {
  it('reports every required field missing on a blank draft', () => {
    expect(getMissingRequiredFields(BLANK)).toEqual([
      'eventName',
      'purpose',
      'startDatetime',
      'endDatetime',
      'expectedAttendance',
      'venueRequirements',
      'accessibilityNeeds',
    ])
    expect(getCompletionPercent(BLANK)).toBe(0)
  })

  it('reports nothing missing once every required field is filled in', () => {
    expect(getMissingRequiredFields(COMPLETE)).toEqual([])
    expect(getCompletionPercent(COMPLETE)).toBe(100)
  })

  it('treats whitespace-only text fields as missing, same as the backend', () => {
    const whitespaceOnly: DraftFields = { ...COMPLETE, eventName: '   ' }
    expect(getMissingRequiredFields(whitespaceOnly)).toEqual(['eventName'])
  })

  it('treats an empty accessibilityNeeds array as missing (must answer, even with "none")', () => {
    const noAnswer: DraftFields = { ...COMPLETE, accessibilityNeeds: [] }
    expect(getMissingRequiredFields(noAnswer)).toEqual(['accessibilityNeeds'])
  })

  it('computes a proportional percentage for a partially filled draft', () => {
    const partial: DraftFields = {
      ...BLANK,
      eventName: 'Q1 Town Hall', // 1 of 7 required fields
    }
    expect(getCompletionPercent(partial)).toBe(Math.round((1 / 7) * 100))
  })

  it('does not count optional fields (description, equipment, registration) toward completion', () => {
    const optionalOnly: DraftFields = {
      ...BLANK,
      description: 'Extra context',
      equipmentRequirements: 'Projector',
      registrationNeeds: true,
    }
    expect(getCompletionPercent(optionalOnly)).toBe(0)
  })
})
