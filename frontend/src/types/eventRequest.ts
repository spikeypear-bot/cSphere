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

export const ACCESSIBILITY_LABELS: Record<AccessibilityFeature, string> = {
  accessible_parking: 'Accessible parking',
  drop_off_zone: 'Drop-off zone',
  public_transport: 'Near public transport',
  step_free_access: 'Step-free access',
  wide_doorways: 'Wide doorways',
  elevators: 'Elevators',
  wheelchair_support: 'Wheelchair support',
}

export type EventRequestStatus = 'draft' | 'pending' | 'approved' | 'rejected' | 'cancelled'

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
  organisation: string
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
}
