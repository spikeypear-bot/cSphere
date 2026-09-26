import { ACCESSIBILITY_LABELS, FIELD_LABELS, type AccessibilityFeature } from '../types/eventRequest'

export interface FieldChange {
  field: string
  label: string
  before: string
  after: string
}

const DATE_FIELDS = new Set(['startDatetime', 'endDatetime'])

/** A captured field value as a person reads it. */
export function formatFieldValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (DATE_FIELDS.has(field) && typeof value === 'string') {
    return new Intl.DateTimeFormat('en-SG', {
      timeZone: 'Asia/Singapore', day: 'numeric', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit', hour12: true,
    }).format(new Date(value))
  }
  if (Array.isArray(value)) {
    return value.length === 0 ? '—'
      : value.map((v) => ACCESSIBILITY_LABELS[v as AccessibilityFeature] ?? String(v)).join(', ')
  }
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return String(value)
}

/** Values compared by meaning, not spelling: the same instant written with
 * different offsets, or the same list in a different order, is unchanged. */
function sameValue(field: string, a: unknown, b: unknown): boolean {
  const empty = (v: unknown) => v === null || v === undefined || v === '' || (Array.isArray(v) && v.length === 0)
  if (empty(a) && empty(b)) return true
  if (DATE_FIELDS.has(field) && typeof a === 'string' && typeof b === 'string') {
    return Date.parse(a) === Date.parse(b)
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    return JSON.stringify([...a].sort()) === JSON.stringify([...b].sort())
  }
  return JSON.stringify(a) === JSON.stringify(b)
}

/**
 * What the organiser changed between two captured moments (EC02: the values
 * when clarification was asked vs when they resubmitted), in form order.
 */
export function changedFields(
  before: Record<string, unknown> | null | undefined,
  after: Record<string, unknown> | null | undefined,
): FieldChange[] {
  if (!before || !after) return []
  return Object.keys(FIELD_LABELS)
    .filter((field) => !sameValue(field, before[field], after[field]))
    .map((field) => ({
      field,
      label: FIELD_LABELS[field],
      before: formatFieldValue(field, before[field]),
      after: formatFieldValue(field, after[field]),
    }))
}
