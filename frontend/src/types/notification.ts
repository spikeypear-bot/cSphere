// Mirrors backend/src/main/java/com/example/connect_sphere/notification/dto/NotificationDto.java

export type NotificationTypeName =
  | 'status_change'
  | 'coordinator_assignment'
  | 'clarification_requested'
  | 'clarification_responded'
  | 'venue_booking_requested'
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
  // EO07
  | 'request_cancelled'
  | 'event_cancellation_requested'
  | 'event_cancellation_approved'
  | 'event_cancellation_declined'
  | 'event_cancelled'

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
