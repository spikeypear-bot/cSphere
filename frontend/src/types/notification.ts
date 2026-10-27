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
  // TS09
  | 'equipment_damage_outcome'
  // TS11
  | 'equipment_request_submitted'
  | 'equipment_change_review'
  // EC-NEW3 (D37)
  | 'equipment_request_attention'

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
}
