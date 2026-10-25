import { apiClient } from '../../../lib/apiClient'
import type {
  CatalogueItem,
  DamageReport,
  DecideOutcomeBody,
  EventEquipmentDamage,
  ReportDamageBody,
} from './equipmentDamage.types'

// TS09. apiClient prefixes /api itself, so paths start below it.
const REPORTS = '/equipment-damage-reports'

export const fetchCatalogue = () => apiClient.get<CatalogueItem[]>('/equipment/catalogue')

/** Every report, newest first; `equipmentId` keeps one kind's (D32). */
export const fetchReports = (equipmentId?: string) =>
  apiClient.get<DamageReport[]>(equipmentId ? `${REPORTS}?equipmentId=${encodeURIComponent(equipmentId)}` : REPORTS)
export const fetchReport = (reportId: string) => apiClient.get<DamageReport>(`${REPORTS}/${reportId}`)
export const reportDamage = (body: ReportDamageBody) => apiClient.post<DamageReport>(REPORTS, body)

export const decideOutcome = (reportId: string, outcomeId: string, body: DecideOutcomeBody) =>
  apiClient.post<DamageReport>(`${REPORTS}/${reportId}/outcomes/${outcomeId}`, body)

export const closeReport = (reportId: string) => apiClient.post<DamageReport>(`${REPORTS}/${reportId}/close`)

export const fetchEventDamage = (eventId: string) =>
  apiClient.get<EventEquipmentDamage[]>(`/events/${eventId}/equipment-damage`)

export const resolveShortfall = (eventId: string, outcomeId: string, note: string) =>
  apiClient.post<EventEquipmentDamage>(`/events/${eventId}/equipment-damage/${outcomeId}/resolve`, { note })
