import { apiClient } from '../../../lib/apiClient'
import type { ChangeReviewDetail, ChangeReviewSummary, ReviewOutcome } from './changeReview.types'

// TS11: Technical Support's reviews of approved changes that affect equipment.

export function fetchChangeReviews(): Promise<ChangeReviewSummary[]> {
  return apiClient.get<ChangeReviewSummary[]>('/equipment-change-reviews')
}

export function fetchChangeReview(reviewId: string): Promise<ChangeReviewDetail> {
  return apiClient.get<ChangeReviewDetail>(`/equipment-change-reviews/${reviewId}`)
}

export function recordOutcome(reviewId: string, outcome: ReviewOutcome, note: string): Promise<ChangeReviewDetail> {
  return apiClient.post<ChangeReviewDetail>(`/equipment-change-reviews/${reviewId}/outcome`, {
    outcome,
    note: note.trim() === '' ? null : note,
  })
}

/** AC12: the outcomes for one event, for its assigned coordinator (and the Lead). */
export function fetchEventChangeReviews(eventId: string): Promise<ChangeReviewSummary[]> {
  return apiClient.get<ChangeReviewSummary[]>(`/events/${eventId}/equipment-change-reviews`)
}
