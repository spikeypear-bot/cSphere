// Mirrors backend/src/main/java/com/example/connect_sphere/equipmentreview/EquipmentChangeReviewDto.java (TS11)

export type ReviewOutcome = 'still_suitable' | 'needs_coordinator_action'

export interface ComparedValues {
  start: string | null
  end: string | null
  equipmentRequirements: string | null
}

export interface ReviewReservation {
  logId: string
  equipmentName: string
  quantity: number
  serialNumber: string | null
  loanedFrom: string
  loanedUntil: string
  /** No longer covered the event when the change was approved, and not yet reviewed. */
  flagged: boolean
}

export interface ChangeReviewSummary {
  reviewId: string
  eventId: string
  eventName: string
  changedAt: string
  needsReview: boolean
  affectedCount: number
  requirementsChanged: boolean
  outcome: ReviewOutcome | null
  outcomeNote: string | null
  reviewedByName: string | null
  reviewedAt: string | null
}

export interface ChangeReviewDetail {
  reviewId: string
  eventId: string
  eventName: string
  eventStart: string
  eventEnd: string
  coordinatorName: string | null
  changedAt: string
  previous: ComparedValues
  updated: ComparedValues
  requirementsChanged: boolean
  affectedAtChange: ReviewReservation[]
  reservations: ReviewReservation[]
  needsReview: boolean
  outcome: ReviewOutcome | null
  outcomeNote: string | null
  reviewedByName: string | null
  reviewedAt: string | null
}

export const OUTCOME_LABELS: Record<ReviewOutcome, string> = {
  still_suitable: 'Still suitable',
  needs_coordinator_action: 'Needs coordinator action',
}
