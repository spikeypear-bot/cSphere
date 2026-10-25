export interface VenueRequirementReviewView {
  review: {
    reviewId: string; bookingId: string; changeRequestId: string; eventId: string
    eventName: string; venueAddress: string; approvedAt: string
    coordinatorName: string | null; coordinatorEmail: string | null
    reservedStart: string; reservedEnd: string; setupDurationMinutes: number; turnaroundDurationMinutes: number
    status: 'pending' | 'suitable' | 'action_required'
    reviewedAt: string | null; reviewerName: string | null; reviewNote: string | null
    reviewedChecks: { key: string; status: string; title: string; detail: string }[] | null
  }
  changes: { field: string; label: string; before: unknown; after: unknown }[]
  checks: { key: string; status: string; title: string; detail: string }[]
  reservedStart: string; reservedEnd: string; setupDurationMinutes: number; turnaroundDurationMinutes: number
  suitability?: {
    checks: { key: string; status: string; title: string; detail: string }[]
    venueRequirements: string | null; supportedLayouts: string[]
    occupiedStart: string; occupiedEnd: string
  }
  outcomeCurrent?: boolean
  bookingStatus?: string
  canReview: boolean
}
