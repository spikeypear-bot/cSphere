// Mirrors backend/src/main/java/com/example/connect_sphere/notification/dto/NotificationDto.java

export type NotificationTypeName =
  | 'status_change'
  | 'coordinator_assignment'
  | 'clarification_requested'
  | 'clarification_responded'
  | 'venue_booking_requested'

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
