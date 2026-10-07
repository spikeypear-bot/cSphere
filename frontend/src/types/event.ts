// Mirrors backend/src/main/java/com/example/connect_sphere/event/dto/EventDto.java
import type { AccessibilityFeature } from './eventRequest'
import type { Facility } from './venue'

export type EventStatus = 'pending' | 'confirmed' | 'cancelled' | 'completed'

// 'pending' is shown as Planning: Week 4 'Event Status Management' names
// "planning" as the stage between approval and confirmation, and that is
// what an approved event is doing (EC02 "Proceed to Planning").
export const EVENT_STATUS_LABELS: Record<EventStatus, string> = {
  pending: 'Planning',
  confirmed: 'Confirmed',
  cancelled: 'Cancelled',
  completed: 'Completed',
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
}
