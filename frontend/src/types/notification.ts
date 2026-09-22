// Mirrors backend/src/main/java/com/example/connect_sphere/notification/dto/NotificationDto.java

export type NotificationTypeName = 'status_change' | 'coordinator_assignment'

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
}
