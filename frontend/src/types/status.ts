// Mirrors backend common/status/StatusView.java (EC-NEW2). Every request and
// event response carries one of these; screens show it as given instead of
// keeping their own label maps.

export type JourneyCode =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CLARIFICATION_REQUIRED'
  | 'REJECTED'
  | 'PLANNING'
  | 'AWAITING_SAFETY_REVIEW'
  | 'SAFETY_CHANGES_REQUESTED'
  | 'SAFETY_APPROVED'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'CANCELLED'

export interface StatusView {
  code: JourneyCode
  label: string
  explanation: string
  nextActor: string | null
  stage: 'REQUEST' | 'EVENT'
  isFinal: boolean
  datePassed: boolean
}

/** The badge colour family for each status. Colour is never the only cue:
 * the label is always shown as text. */
export const JOURNEY_TONE: Record<JourneyCode, 'neutral' | 'progress' | 'attention' | 'success' | 'danger'> = {
  DRAFT: 'neutral',
  SUBMITTED: 'progress',
  UNDER_REVIEW: 'progress',
  CLARIFICATION_REQUIRED: 'attention',
  REJECTED: 'danger',
  PLANNING: 'progress',
  AWAITING_SAFETY_REVIEW: 'attention',
  SAFETY_CHANGES_REQUESTED: 'attention',
  SAFETY_APPROVED: 'success',
  CONFIRMED: 'success',
  COMPLETED: 'neutral',
  CANCELLED: 'danger',
}

/** Labels for timeline entries, which store journey codes (EC-NEW2). */
export const JOURNEY_LABELS: Record<JourneyCode, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under Review',
  CLARIFICATION_REQUIRED: 'Clarification Required',
  REJECTED: 'Rejected',
  PLANNING: 'Planning',
  AWAITING_SAFETY_REVIEW: 'Awaiting Safety Review',
  SAFETY_CHANGES_REQUESTED: 'Safety Changes Requested',
  SAFETY_APPROVED: 'Safety Approved',
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
}
