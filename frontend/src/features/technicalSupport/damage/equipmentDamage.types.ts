// Mirrors backend/src/main/java/com/example/connect_sphere/equipmentdamage/dto/
// (TS09). See docs/equipment-damage-api.md for the rules behind each field.

export type DamageOutcome = 'COVERED' | 'REPLACED' | 'SHORT'
export type ShortfallResolution = 'NOTE' | 'ADDITIONAL_REQUEST'
export type DamageDecision = 'COVERED' | 'REPLACE' | 'SHORT' | 'REDUCE'
/** V39: the TS03 block put on the unit when the report closes. */
export type DamageBlockStatus = 'FAULTY' | 'UNAVAILABLE'

export interface AffectedEvent {
  outcomeId: string
  eventId: string
  eventName: string
  eventStart: string
  eventEnd: string
  journeyStatus: string
  requestedQuantity: number
  reservedQuantity: number
  affectedQuantity: number
  spareCovers: boolean
  /** False once the event is cancelled or over; it then needs no outcome. */
  active: boolean
  outcome: DamageOutcome | null
  shortfall: number
  reducedBy: number
  replacementSerialNumber: string | null
  decidedAt: string | null
  resolution: ShortfallResolution | null
  resolutionNote: string | null
  resolvedAt: string | null
  additionalRequestId: string | null
  additionalRequestStatus: string | null
}

export interface DamageReport {
  reportId: string
  equipmentId: string
  equipmentName: string
  serialised: boolean
  serialNumber: string | null
  quantity: number
  description: string
  periodStart: string
  periodEnd: string | null
  markFaulty: boolean
  faultyMarked: boolean
  status: 'OPEN' | 'CLOSED'
  reportedBy: string
  reportedByName: string
  reportedAt: string
  closedAt: string | null
  canClose: boolean
  affectedEvents: AffectedEvent[]
  /** V39; older responses (and fixtures) only carry markFaulty/faultyMarked. */
  blockStatus?: DamageBlockStatus | null
  blockApplied?: boolean
}

/** The block a report asks for, reading the older markFaulty flag too. */
export function reportBlock(report: DamageReport): { status: DamageBlockStatus; applied: boolean } | null {
  const status = report.blockStatus ?? (report.markFaulty ? 'FAULTY' : null)
  if (!status) return null
  return { status, applied: report.blockApplied ?? report.faultyMarked }
}

export const BLOCK_LABELS: Record<DamageBlockStatus, string> = { FAULTY: 'Faulty', UNAVAILABLE: 'Unavailable' }

export interface ReportDamageBody {
  equipmentId: string
  serialNumber: string | null
  quantity: number | null
  description: string
  periodStart: string
  periodEnd: string | null
  /** Serialised only; omitted (or null) for no block. */
  blockStatus?: DamageBlockStatus | null
}

export interface DecideOutcomeBody {
  decision: DamageDecision
  replacementSerialNumber: string | null
  reduceBy: number | null
}

/** One damaged item as the event's coordinator (or the Lead) sees it. */
export interface EventEquipmentDamage {
  outcomeId: string
  reportId: string
  equipmentId: string
  equipmentName: string
  serialNumber: string | null
  damagedQuantity: number
  description: string
  periodStart: string
  periodEnd: string | null
  reportedAt: string
  /** Null while Technical Support is still deciding. */
  outcome: DamageOutcome | null
  shortfall: number
  replacementSerialNumber: string | null
  decidedAt: string | null
  resolution: ShortfallResolution | null
  resolutionNote: string | null
  resolvedAt: string | null
  additionalRequestId: string | null
  additionalRequestStatus: string | null
  canRequestAdditional: boolean
  canResolve: boolean
}

export interface CatalogueItem {
  equipmentId: string
  equipmentName: string
  totalQuantity: number
  serialised: boolean
}

export interface EquipmentUnitStatus {
  equipmentId: string
  equipmentName: string
  serialNumber: string
  status: 'Available' | 'Faulty' | 'Unavailable'
}

/** The outcome in words, the same for Technical Support and the coordinator. */
export function outcomeLabel(outcome: DamageOutcome | null, shortfall: number, replacement: string | null): string {
  if (outcome === 'COVERED') return 'Covered by spare'
  if (outcome === 'REPLACED') return replacement ? `Replaced with unit ${replacement}` : 'Replaced'
  if (outcome === 'SHORT') return `Short by ${shortfall}`
  return 'Awaiting a decision'
}

export const DESCRIPTION_MAX = 2000
