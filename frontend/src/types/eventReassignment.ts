export type EventReassignmentStatus = 'pending' | 'approved' | 'declined'

export interface EventReassignmentRequestDto {
  reassignmentRequestId: string
  eventId: string
  eventName: string
  organisation: string | null
  eventStartDatetime: string
  eventEndDatetime: string
  requesterUserId: string
  requesterName: string
  reason: string
  status: EventReassignmentStatus
  requestedAt: string
  decidedAt: string | null
  decisionReason: string | null
  coordinatorUserId: string | null
  coordinatorName: string | null
}
