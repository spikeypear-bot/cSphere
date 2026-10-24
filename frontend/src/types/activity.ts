// Mirrors backend/src/main/java/com/example/connect_sphere/activity/dto/ActivityDto.java

export type ActivityTypeName =
  | 'venue_requirements_reviewed'
  | 'submitted'
  | 'coordinator_assigned'
  | 'coordinator_reassigned'
  | 'clarification_requested'
  | 'clarification_responded'
  | 'approved'
  | 'rejected'
  | 'venue_booking_requested'
  | 'venue_hold_created'
  | 'venue_hold_released'
  | 'venue_hold_expired'
  | 'venue_hold_converted'
  | 'venue_booking_cancelled'
  | 'venue_booking_replaced'
  | 'venue_added'
  | 'venue_booking_approved'
  | 'venue_booking_rejected'
  | 'venue_alternative_suggested'
  | 'venue_unavailable'
  | 'equipment_requested'
  | 'equipment_approved'
  | 'equipment_rejected'
  | 'sent_for_safety_review'
  | 'safety_review_withdrawn'
  | 'safety_approved'
  | 'safety_changes_requested'
  | 'safety_rejected'
  | 'returned_to_planning'
  | 'event_confirmed'
  | 'event_completed'
  | 'event_reassignment_requested'
  | 'event_reassignment_approved'
  | 'event_reassignment_declined'
  | 'change_requested'
  | 'change_request_withdrawn'
  | 'change_approved'
  | 'change_rejected'

/** One entry of a request's timeline (V13 event_request_activity). The
 * server has already filtered out entries the viewer's role may not see. */
export interface ActivityDto {
  /** Null for an entry no one performed (the event becoming Completed). */
  activityId: string | null
  type: ActivityTypeName
  actorName: string
  actorRole: string | null
  message: string | null
  flaggedFields: string[]
  fromStatus: string | null
  toStatus: string | null
  occurredAt: string
  /** V14: one question per flagged field (clarification_requested). */
  fieldQuestions?: Record<string, string> | null
  /** V14: field values at this moment (clarification requested/responded). */
  fieldValues?: Record<string, unknown> | null
  /** EC-NEW5: decided on the server; Major changes may affect arrangements already made. */
  significance?: 'major' | 'normal'
}
