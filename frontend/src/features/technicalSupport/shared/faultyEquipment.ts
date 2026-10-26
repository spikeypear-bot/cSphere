import type { DamageReport } from '../damage/equipmentDamage.types'
import type { UnitStatusPeriod } from '../status/equipmentStatus.types'

/** One thing out of action: a TS03 block on a unit, or an open TS09 report. */
export interface FaultyEntry {
  key: string
  kind: 'block' | 'report'
  equipmentId: string
  equipmentName: string
  /** "PJ-1", or "5 units" for a non-serialised report. */
  what: string
  /** Faulty / Unavailable for a block; "Damage report" for a report. */
  label: string
  start: string
  end: string | null
  description: string | null
  /** Where the entry opens: its report, or the kind's inventory page. */
  linkTo: string
}

export const TS = '/technical-support'
export const reportPath = (reportId: string) => `${TS}/faulty/reports/${reportId}`
export const equipmentPath = (equipmentId: string) => `${TS}/inventory/${equipmentId}`

/** Current blocks and open reports, newest start first. Closed reports are
 * history, not out of action, so they are left out. */
export function mergeFaulty(periods: UnitStatusPeriod[], reports: DamageReport[]): FaultyEntry[] {
  const blocks: FaultyEntry[] = periods.map((period) => ({
    key: `block:${period.id}`,
    kind: 'block',
    equipmentId: period.equipmentId,
    equipmentName: period.equipmentName,
    what: period.serialNumber,
    label: period.status,
    start: period.start,
    end: period.end,
    description: null,
    linkTo: equipmentPath(period.equipmentId),
  }))
  const open: FaultyEntry[] = reports
    .filter((report) => report.status === 'OPEN')
    .map((report) => ({
      key: `report:${report.reportId}`,
      kind: 'report',
      equipmentId: report.equipmentId,
      equipmentName: report.equipmentName,
      what: report.serialNumber ?? `${report.quantity} unit${report.quantity === 1 ? '' : 's'}`,
      label: 'Damage report',
      start: report.periodStart,
      end: report.periodEnd,
      description: report.description,
      linkTo: reportPath(report.reportId),
    }))
  return [...blocks, ...open].sort((a, b) => new Date(b.start).getTime() - new Date(a.start).getTime())
}
