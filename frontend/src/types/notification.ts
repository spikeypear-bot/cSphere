// Mirrors backend/src/main/java/com/example/connect_sphere/notification/dto/NotificationDto.java

export type NotificationTypeName =
  | 'venue_suitability_changed'
  | 'venue_arrangements_changed'
  | 'status_change'
  | 'coordinator_assignment'
  | 'clarification_requested'
  | 'clarification_responded'
  | 'venue_requirement_reviewed'
  | 'venue_requirement_changed'
  | 'venue_booking_requested'
  | 'venue_booking_rejected'
  | 'venue_booking_approved'
  | 'venue_booking_cancelled'
  | 'venue_unavailable'
  | 'event_confirmed'
  | 'event_returned_to_planning'
  | 'event_safety_approved'
  | 'venue_hold_expired'
  | 'venue_hold_expiring'
  | 'venue_timing_changed'
  | 'venue_timing_conflict'
  | 'event_reassignment_requested'
  | 'event_reassignment_approved'
  | 'event_reassignment_declined'
  | 'change_request_submitted'
  | 'change_request_withdrawn'
  | 'change_request_decided'
  // EO07
  | 'request_cancelled'
  | 'event_cancellation_requested'
  | 'event_cancellation_approved'
  | 'event_cancellation_declined'
  | 'event_cancelled'
  | 'attendee_registration_confirmed'
  | 'attendee_registration_withdrawn'
  // TS09
  | 'equipment_damage_outcome'
  // TS11
  | 'equipment_request_submitted'
  | 'equipment_change_review'
  // EC-NEW3 (D37)
  | 'equipment_request_attention'
  // EC-NEW4 (V54)
  | 'request_submitted'
  | 'request_assigned'
  | 'request_handed_over'
  | 'equipment_request_decided'
  // EO-C1 (V54)
  | 'confirmed_venue_changed'
  | 'attendee_event_changed'
  | 'attendee_event_cancelled'

export interface NotificationChange {
  field: string
  label: string
  previousValue: string
  updatedValue: string
}

export interface NotificationDto {
  notificationId: string
  type: NotificationTypeName
  eventRequestId: string | null
  eventId: string | null
  eventName: string
  newStatus: string | null
  reason: string | null
  coordinatorName: string | null
  coordinatorEmail: string | null
  isReassignment: boolean | null
  occurredAt: string
  read: boolean
  linkPath: string | null
  /** The clarification message or organiser response, where there is one. */
  message?: string | null
  /** EC-NEW4: who did it, and their role claim; null when the system did. */
  actorName?: string | null
  actorRole?: string | null
  /** AT09: the approved comparison, captured when the change was committed. */
  changes?: NotificationChange[] | null
  /** AT09: event times before cancellation, not a live event lookup. */
  originalEventStartDatetime?: string | null
  originalEventEndDatetime?: string | null
}
