// EO03: the changeable details, compared and shown the way the server does
// (ChangeFields.java), so the review step lists exactly what the server will.
import { formatFieldValue } from '../../lib/fieldChanges'
import { HOME_BY_ROLE } from '../../lib/sessionContext'
import type { EventRequestDto, SaveEventRequestRequest } from '../../types/eventRequest'
import { venueFacilityLabels, type Facility } from '../../types/venue'

export const CHANGE_FIELD_LABELS: Record<string, string> = {
  eventName: 'Event name',
  purpose: 'Purpose',
  description: 'Description',
  startDatetime: 'Start',
  endDatetime: 'End',
  expectedAttendance: 'Expected attendance',
  venueRequirements: 'Venue requirements',
  equipmentRequirements: 'Equipment requirements',
  accessibilityNeeds: 'Accessibility needs',
  requiredFacilities: 'Required facilities',
  registrationNeeds: 'Registration',
  registrationCapacity: 'Registration capacity',
  registrationOpensAt: 'Registration opens',
  attendeeInstructions: 'Attendee instructions',
}

/** Week 4: a change to these may mean existing arrangements must be reconsidered. */
export const IMPORTANT_FIELDS = new Set(['startDatetime', 'endDatetime', 'expectedAttendance',
  'venueRequirements', 'equipmentRequirements'])

/** These, plus accessibility, send an event in the safety stages back to Planning on approval. */
export const AFFECTS_ARRANGEMENTS = new Set([...IMPORTANT_FIELDS, 'accessibilityNeeds', 'requiredFacilities'])

export type ChangeDetails = Required<Pick<SaveEventRequestRequest, 'eventName' | 'purpose' | 'description'
  | 'startDatetime' | 'endDatetime' | 'expectedAttendance' | 'venueRequirements' | 'equipmentRequirements'
  | 'accessibilityNeeds' | 'requiredFacilities' | 'registrationNeeds'>>
  & Pick<SaveEventRequestRequest, 'registrationCapacity' | 'registrationOpensAt' | 'attendeeInstructions'>

export function detailsOf(r: EventRequestDto): ChangeDetails {
  return {
    eventName: r.eventName, purpose: r.purpose, description: r.description,
    startDatetime: r.startDatetime, endDatetime: r.endDatetime, expectedAttendance: r.expectedAttendance,
    venueRequirements: r.venueRequirements, equipmentRequirements: r.equipmentRequirements,
    accessibilityNeeds: r.accessibilityNeeds ?? [], requiredFacilities: r.requiredFacilities ?? [],
    registrationNeeds: r.registrationNeeds,
    registrationCapacity: r.registrationCapacity ?? null,
    registrationOpensAt: r.registrationOpensAt ?? null,
    attendeeInstructions: r.attendeeInstructions ?? null,
  }
}

function empty(v: unknown): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '') || (Array.isArray(v) && v.length === 0)
}

/** Same meaning, not same spelling: one moment in two offsets, one list in two orders, surrounding spaces. */
export function sameMeaning(field: string, a: unknown, b: unknown): boolean {
  if (empty(a) && empty(b)) return true
  if ((field.endsWith('Datetime') || field === 'registrationOpensAt') && typeof a === 'string' && typeof b === 'string') return Date.parse(a) === Date.parse(b)
  if (Array.isArray(a) && Array.isArray(b)) return [...a].map(String).sort().join('|') === [...b].map(String).sort().join('|')
  if (typeof a === 'string' && typeof b === 'string') return a.trim() === b.trim()
  if (typeof a === 'number' && typeof b === 'number') return a === b
  return a === b
}

export interface ShownChange {
  field: string
  label: string
  before: string
  after: string
}

export function showValue(field: string, value: unknown): string {
  if (field === 'requiredFacilities' && Array.isArray(value)) {
    return value.length === 0 ? '—' : value.map((v) => venueFacilityLabels[v as Facility] ?? String(v)).join(', ')
  }
  if ((field.endsWith('Datetime') || field === 'registrationOpensAt') && typeof value === 'string' && value) {
    return `${formatFieldValue('startDatetime', value)} (Singapore time)`
  }
  return formatFieldValue(field, value)
}

/** The fields whose meaning changed, in the form's order, as a person reads them. */
export function differences(before: Partial<ChangeDetails>, after: Partial<ChangeDetails>): ShownChange[] {
  return Object.entries(CHANGE_FIELD_LABELS)
    .filter(([field]) => !sameMeaning(field, before[field as keyof ChangeDetails], after[field as keyof ChangeDetails]))
    .map(([field, label]) => ({
      field, label,
      before: showValue(field, before[field as keyof ChangeDetails]),
      after: showValue(field, after[field as keyof ChangeDetails]),
    }))
}

/** The server's field changes (raw values), shown the same way. */
export function shownChanges(changes: { field: string; label: string; before: unknown; after: unknown }[]): ShownChange[] {
  return changes.map((c) => ({ field: c.field, label: c.label, before: showValue(c.field, c.before), after: showValue(c.field, c.after) }))
}

/** The calendar date of an instant in Singapore. */
export function singaporeDate(iso: string | null): string | null {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isFinite(t) ? new Date(t + 8 * 3_600_000).toISOString().slice(0, 10) : null
}

/** Where each role reads a request's change requests. */
export function changesPathFor(role: string | null, requestId: string): string {
  return `${role ? HOME_BY_ROLE[role as keyof typeof HOME_BY_ROLE] : ''}/requests/${requestId}/changes`
}
