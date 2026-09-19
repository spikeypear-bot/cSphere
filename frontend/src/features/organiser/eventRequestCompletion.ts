// EO02 UX enhancement: a live view of "how complete is this draft" that
// mirrors EventRequestService.missingRequiredFields() exactly (backend/src/
// main/java/.../eventrequest/service/EventRequestService.java), so the
// organiser sees the same requirement client-side, continuously, rather than
// only learning what's missing after a blocked submit attempt. This is a
// client-side mirror for immediate feedback — the backend check on submit is
// still the actual source of truth and cannot be bypassed from here.
import { REQUIRED_FIELD_KEYS } from '../../types/eventRequest'
import type { DraftFields } from './useEventRequestDraft'

function isBlank(value: string | null | undefined): boolean {
  return value == null || value.trim().length === 0
}

/** One field's required-ness check, keyed the same way as REQUIRED_FIELD_KEYS
 * / REQUIRED_FIELD_LABELS / the backend's missingFields response. */
const FIELD_IS_MISSING: Record<string, (fields: DraftFields) => boolean> = {
  eventName: (f) => isBlank(f.eventName),
  purpose: (f) => isBlank(f.purpose),
  startDatetime: (f) => isBlank(f.startDatetime),
  endDatetime: (f) => isBlank(f.endDatetime),
  expectedAttendance: (f) => f.expectedAttendance == null,
  venueRequirements: (f) => isBlank(f.venueRequirements),
  accessibilityNeeds: (f) => f.accessibilityNeeds.length === 0,
}

export function getMissingRequiredFields(fields: DraftFields): string[] {
  return REQUIRED_FIELD_KEYS.filter((key) => FIELD_IS_MISSING[key]?.(fields))
}

/** Rounded 0-100. Used for both the wizard's live tracker and the draft
 * list's completion ring — one calculation, one source of truth. */
export function getCompletionPercent(fields: DraftFields): number {
  const missing = getMissingRequiredFields(fields).length
  const total = REQUIRED_FIELD_KEYS.length
  return Math.round(((total - missing) / total) * 100)
}
