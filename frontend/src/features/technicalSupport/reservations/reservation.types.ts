export type EquipmentRequestStatus = 'processing' | 'approved' | 'rejected'
/** What `GET /api/equipment-requests?status=` accepts (D32). */
export type EquipmentRequestFilter = EquipmentRequestStatus | 'all'
export const EQUIPMENT_REQUEST_FILTERS: EquipmentRequestFilter[] = ['processing', 'approved', 'rejected', 'all']
/** Words for the request status the server sends (lowercase codes). */
export const EQUIPMENT_REQUEST_FILTER_LABELS: Record<EquipmentRequestFilter, string> = {
  processing: 'Awaiting review',
  approved: 'Approved',
  rejected: 'Rejected',
  all: 'All',
}

// AC 1: an event that needs equipment (status = processing). The fields after
// `status` were added for the console (D32, docs/equipment-damage-api.md);
// they are optional because older callers and fixtures do not send them.
export interface EquipmentRequestSummary {
  requestId: string
  eventId: string
  eventName: string
  eventStart: string
  eventEnd: string
  technicalRequirement: string
  status: EquipmentRequestStatus
  organisationName?: string | null
  /** The event's currently assigned coordinator (a username), or null. */
  requestedBy?: { userId: string; name: string } | null
  /** Not stored yet, so always null: never shown. */
  submittedAt?: string | null
  /** TS09 AC19: an additional request answering an equipment shortfall. */
  isShortfallRequest?: boolean
  items?: RequestLine[]
  eventStatus?: string
}

export interface EquipmentRequestDecision {
  requestId: string
  eventId: string
  status: 'processing' | 'approved' | 'rejected'
  technicalRequirement: string
  rejectReason: string | null
  lines: RequestLine[]
}

// AC 2: one line of what an event's equipment request asks for
export interface RequestLine {
  equipmentId: string
  equipmentName: string
  quantity: number
}

// AC 3 + 4: availability for one equipment type, for a chosen period
export interface EquipmentAvailability {
  equipmentId: string
  equipmentName: string
  serialised: boolean
  totalQuantity: number
  availableQuantity: number
}

// AC 11: a reservation already saved for an event
export interface EquipmentReservation {
  logId: string
  eventId: string
  equipmentId: string
  equipmentName: string
  quantity: number
  serialNumber: string | null
  loanedFrom: string
  loanedUntil: string
  /** Added with the console (D32); older fixtures leave it out. */
  eventName?: string
}

// Values from <input type="datetime-local">, e.g. "2026-09-25T09:00"
export interface TimePeriod {
  start: string
  end: string
}