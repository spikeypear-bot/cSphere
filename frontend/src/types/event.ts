// Mirrors backend/src/main/java/com/example/connect_sphere/event/dto/EventDto.java
import type { AccessibilityFeature } from './eventRequest'
import type { Facility } from './venue'
import type { StatusView } from './status'

export type EventStatus = 'pending' | 'awaiting_safety_review' | 'safety_changes_requested' | 'safety_approved'
  | 'confirmed' | 'cancelled' | 'completed'

// 'pending' is shown as Planning: Week 4 'Event Status Management' names
// "planning" as the stage between approval and confirmation, and that is
// what an approved event is doing (EC02 "Proceed to Planning").
export const EVENT_STATUS_LABELS: Record<EventStatus, string> = {
  pending: 'Planning',
  awaiting_safety_review: 'Awaiting Safety Review',
  safety_changes_requested: 'Safety Changes Requested',
  safety_approved: 'Safety Approved',
  confirmed: 'Confirmed',
  cancelled: 'Cancelled',
  completed: 'Completed',
}

/** EC08: venues and equipment can still be changed, in Planning or after the
 * Safety Officer asked for changes. Mirrors EventStatus.isPlanningEditable(). */
export function isPlanningEditable(status: EventStatus): boolean {
  return status === 'pending' || status === 'safety_changes_requested'
}

export interface EventDto {
  eventId: string
  eventName: string
  purpose: string
  description: string | null
  startDatetime: string
  endDatetime: string
  expectedAttendance: number
  venueId: string | null
  accessibilityNeeds: AccessibilityFeature[]
  requiredFacilities?: Facility[]
  registrationNeeds: boolean | null
  organisation: string | null
  venueRequirements: string
  equipmentRequirements: string | null
  status: EventStatus
  coordinatorName: string | null
  coordinatorEmail: string | null
  /** EC-NEW2/EC08 */
  journeyStatus?: StatusView | null
  safetyFeedback?: string | null
  safetyApprovedAt?: string | null
  confirmedAt?: string | null
}

/** EC08: GET /api/events/{id}/lifecycle (the Safety Officer checklist is /readiness) */
export interface EventReadinessDto {
  journeyStatus: StatusView
  items: { key: 'venues' | 'equipment' | 'safety'; label: string; state: 'complete' | 'outstanding' | 'waiting' | 'not_needed'; detail: string; actionPath: string | null }[]
  canSendForReview: boolean
  canWithdraw: boolean
  canConfirm: boolean
  blockers: string[]
  safetyFeedback: string | null
  datePassed: boolean
}

/** EO04: GET /api/events/{id}/arrangements */
export interface OrganiserArrangementsDto {
  eventId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  expectedAttendance: number
  journeyStatus: StatusView
  view: 'not_confirmed' | 'confirmed' | 'reconfirming' | 'completed' | 'cancelled'
  coordinatorName: string | null
  coordinatorEmail: string | null
  accessibilityNeeds: string[]
  venues: { venueId: string; name: string; capacity: number | null; layouts: string[]; accessibilities: string[]; facilities: string[]; operatingInformation: string | null; noLongerAvailable: boolean }[]
  equipment: { name: string; quantity: number }[]
  safetyApprovedAt: string | null
  firstConfirmedAt: string | null
  confirmedAt: string | null
}

/** ECL-C7: GET /api/events/by-coordinator (Lead only). */
export interface CoordinatorEventsDto {
  coordinatorId: string
  coordinatorName: string
  events: { requestId: string | null; event: EventDto }[]
}

export type RegistrationState = 'not_required' | 'not_yet_open' | 'open' | 'closed' | 'full'
export type RegistrationStatus = 'confirmed' | 'withdrawn' | 'event_cancelled'

export interface RegistrationInfoDto {
  eventId: string
  eventName: string
  registrationRequired: boolean
  registrationState: RegistrationState
  capacity: number | null
  confirmedCount: number
  placesRemaining: number | null
  opensAt: string | null
  closesAt: string | null
  message: string | null
  attendees: {
    registrationId: string
    name: string
    reference: string
    registeredAt: string
    status: RegistrationStatus
  }[]
}
