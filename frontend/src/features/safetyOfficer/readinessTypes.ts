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
  approvalStatus: 'not_reviewed' | 'approved' | 'rejected'
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
  status: 'pending' | 'confirmed'
}
