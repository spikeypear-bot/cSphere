import type { StatusView } from '../../types/status'

export interface ReadinessItem {
  itemKey: string
  label: string
  checked: boolean
  note: string
  checkedBy: string | null
  checkedAt: string | null
  updatedBy: string | null
  updatedAt: string | null
}

export interface ReadinessDto {
  eventId: string
  // 'changes_requested' added with EC08 (V23).
  approvalStatus: 'not_reviewed' | 'approved' | 'rejected' | 'changes_requested'
  approvalNote: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  completedItems: number
  totalItems: number
  items: ReadinessItem[]
}

export interface SafetyEventListItem {
  eventId: string
  eventName: string
  startDatetime: string
  endDatetime: string
  venueAddress: string | null
  // The safety stages are EC08's (V23); see EVENT_STATUS_LABELS.
  status: 'pending' | 'awaiting_safety_review' | 'safety_changes_requested' | 'safety_approved' | 'confirmed'
  // EC-NEW2: the label every other role sees for this event.
  journeyStatus?: StatusView
}
