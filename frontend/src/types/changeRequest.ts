// Mirrors backend/src/main/java/com/example/connect_sphere/changerequest/ChangeRequestDtos.java

export type ChangeRequestStatus = 'pending' | 'approved' | 'rejected' | 'withdrawn'

export interface FieldChange {
  field: string
  label: string
  before: unknown
  after: unknown
}

/** EC-NEW3: one thing the change would affect; every "attention" item must be acknowledged to approve. */
export interface ImpactItem {
  key: string
  area: 'arrangements' | 'venue' | 'equipment' | 'safety' | string
  status: 'ok' | 'attention'
  title: string
  detail: string
}

export interface ChangeRequestView {
  changeRequestId: string
  requestId: string
  eventId: string | null
  eventName: string
  organisation: string
  /** The request's or event's stored status right now. */
  currentStatus: string
  status: ChangeRequestStatus
  /** Why a pending change can no longer be decided (e.g. the event has started). */
  closedReason: string | null
  reason: string
  requestedByName: string
  requestedAt: string
  decidedByName: string | null
  decidedAt: string | null
  decisionReason: string | null
  changes: FieldChange[]
  /** Calculated now, for the coordinator and Lead, while pending. */
  impact: ImpactItem[] | null
  /** Once approved: what the coordinator acknowledged, still to follow up. */
  followUps: ImpactItem[]
  canDecide: boolean
  canWithdraw: boolean
}

export const CHANGE_STATUS_LABELS: Record<ChangeRequestStatus, string> = {
  pending: 'Waiting for a decision',
  approved: 'Approved',
  rejected: 'Rejected',
  withdrawn: 'Withdrawn',
}
