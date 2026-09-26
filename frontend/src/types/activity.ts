// Mirrors backend/src/main/java/com/example/connect_sphere/activity/dto/ActivityDto.java

export type ActivityTypeName =
  | 'submitted'
  | 'coordinator_assigned'
  | 'clarification_requested'
  | 'clarification_responded'
  | 'approved'
  | 'rejected'
  | 'venue_booking_requested'
  | 'venue_booking_cancelled'

/** One entry of a request's timeline (V13 event_request_activity). The
 * server has already filtered out entries the viewer's role may not see. */
export interface ActivityDto {
  activityId: string
  type: ActivityTypeName
  actorName: string
  actorRole: string
  message: string | null
  flaggedFields: string[]
  fromStatus: string | null
  toStatus: string | null
  occurredAt: string
  /** V14: one question per flagged field (clarification_requested). */
  fieldQuestions?: Record<string, string> | null
  /** V14: field values at this moment (clarification requested/responded). */
  fieldValues?: Record<string, unknown> | null
}
