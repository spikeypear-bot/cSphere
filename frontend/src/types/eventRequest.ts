// Mirrors backend/src/main/java/com/example/connect_sphere/eventrequest/dto/*
// and common/enums/AccessibilityFeature.java — keep these in sync by hand for
// now (see docs/decision-log.md for why there's no shared-schema codegen yet).

export type AccessibilityFeature =
  | 'accessible_parking'
  | 'drop_off_zone'
  | 'public_transport'
  | 'step_free_access'
  | 'wide_doorways'
  | 'elevators'
  | 'wheelchair_support'
  // Means "asked, and none are needed" — not the absence of an answer. Lets
  // EO02's "is required to provide accessibility requirements before
  // submitting" be enforced honestly, without forcing an unrelated pick when
  // an event genuinely has no accessibility needs.
  | 'none'

export const ACCESSIBILITY_LABELS: Record<AccessibilityFeature, string> = {
  accessible_parking: 'Accessible parking',
  drop_off_zone: 'Drop-off zone',
  public_transport: 'Near public transport',
  step_free_access: 'Step-free access',
  wide_doorways: 'Wide doorways',
  elevators: 'Elevators',
  wheelchair_support: 'Wheelchair support',
  none: 'No accessibility requirements needed',
}

export type EventRequestStatus =
  | 'draft'
  | 'pending'
  // EC01: the coordinator asked for more information; EO26 resubmits it.
  | 'clarification_required'
  | 'approved'
  | 'rejected'
  | 'cancelled'

/** Every field a coordinator can flag for clarification (EC01), keyed the
 * same as SaveEventRequestRequest so a flag maps straight onto a form field. */
export const FIELD_LABELS: Record<string, string> = {
  eventName: 'Event name',
  purpose: 'Purpose',
  description: 'Description',
  startDatetime: 'Start date & time',
  endDatetime: 'End date & time',
  expectedAttendance: 'Expected attendance',
  venueRequirements: 'Venue requirements',
  equipmentRequirements: 'Equipment requirements',
  accessibilityNeeds: 'Accessibility requirements',
  registrationNeeds: 'Registration',
}

export interface EventRequestDto {
  requestId: string
  requestType: string | null
  eventId: string | null
  eventName: string | null
  purpose: string | null
  description: string | null
  startDatetime: string | null
  endDatetime: string | null
  expectedAttendance: number | null
  venueRequirements: string | null
  equipmentRequirements: string | null
  accessibilityNeeds: AccessibilityFeature[]
  registrationNeeds: boolean | null
  status: EventRequestStatus
  createdAt: string
  updatedAt: string
  organisation: string
  coordinatorId: string | null
  rejectionReason: string | null
  /** Username of the organiser who created the request. */
  createdByName?: string | null
}

/** Every field optional — a draft may be saved incomplete (EO01). */
export interface SaveEventRequestRequest {
  eventName?: string | null
  purpose?: string | null
  description?: string | null
  startDatetime?: string | null
  endDatetime?: string | null
  expectedAttendance?: number | null
  venueRequirements?: string | null
  equipmentRequirements?: string | null
  accessibilityNeeds?: AccessibilityFeature[]
  registrationNeeds?: boolean | null
}

/** The field names here match SaveEventRequestRequest's keys, which is what
 * EventRequestService.submit() (backend) reports as missing — keeping the two
 * in the same shape means the UI can highlight the right field directly. */
export const REQUIRED_FIELD_LABELS: Record<string, string> = {
  eventName: 'Event name',
  purpose: 'Purpose',
  startDatetime: 'Start date & time',
  endDatetime: 'End date & time',
  expectedAttendance: 'Expected attendance',
  venueRequirements: 'Venue requirements',
  accessibilityNeeds: 'Accessibility requirements',
}

/** Same fields, in wizard-step order — the single list every "how complete is
 * this draft" UI (the live tracker, the completion-% ring) reads from, so it
 * can never drift from what REQUIRED_FIELD_LABELS names. */
export const REQUIRED_FIELD_KEYS = Object.keys(REQUIRED_FIELD_LABELS)
