// Mirrors backend/src/main/java/com/example/connect_sphere/event/dto/EventDto.java
import type { AccessibilityFeature } from './eventRequest'

export type EventStatus = 'pending' | 'confirmed' | 'cancelled' | 'completed'

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
  registrationNeeds: boolean | null
  organisation: string | null
  venueRequirements: string
  equipmentRequirements: string | null
  status: EventStatus
  coordinatorName: string | null
  coordinatorEmail: string | null
}
