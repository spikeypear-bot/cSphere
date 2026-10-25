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
  registrationCapacity: (f) => Boolean(f.registrationNeeds) && f.registrationCapacity == null,
  registrationOpensAt: (f) => Boolean(f.registrationNeeds) && isBlank(f.registrationOpensAt),
  registrationClosesAt: (f) => Boolean(f.registrationNeeds) && isBlank(f.registrationClosesAt),
}

export function getMissingRequiredFields(fields: DraftFields): string[] {
  return REQUIRED_FIELD_KEYS.filter((key) => FIELD_IS_MISSING[key]?.(fields))
}

/** Rounded 0-100. Used for both the wizard's live tracker and the draft
 * list's completion ring — one calculation, one source of truth. */
export function getCompletionPercent(fields: DraftFields): number {
  // Registration details become required only when the organiser opts into
  // attendee registration. Excluding inactive conditional fields from both
  // the missing count and denominator prevents them making a blank draft
  // appear partially complete.
  const requiredKeys = REQUIRED_FIELD_KEYS.filter((key) =>
    !['registrationCapacity', 'registrationOpensAt', 'registrationClosesAt'].includes(key)
      || fields.registrationNeeds === true,
  )
  const missing = requiredKeys.filter((key) => FIELD_IS_MISSING[key]?.(fields)).length
  const total = requiredKeys.length
  return Math.round(((total - missing) / total) * 100)
}
