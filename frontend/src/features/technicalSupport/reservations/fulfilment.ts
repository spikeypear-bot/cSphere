import type { EquipmentStatus, EquipmentUnit } from '../status/equipmentStatus.types'
import type { BatchReserveLine, EquipmentAvailability, EquipmentReservation, RequestLine } from './reservation.types'
import { reservedForEventPeriod } from './reservationUtils'

/** What one unit of a serialised kind is doing during the loan window. */
export type UnitState =
  | { kind: 'free' }
  | { kind: 'here'; spare: boolean }
  | { kind: 'elsewhere'; eventName: string | null }
  | { kind: 'blocked'; status: Exclude<EquipmentStatus, 'Available'> }

export interface UnitView {
  serialNumber: string
  state: UnitState
}

/** One requested item on the reserve table (TS01 + TS02, D33). */
export interface FulfilmentRow {
  line: RequestLine
  serialised: boolean
  /** Units that can still be reserved for the window (the server's figure, D32); null if unknown. */
  available: number | null
  /** Reserved for the whole event period (TS06's rule), requested units and spares apart. */
  reserved: number
  spareReserved: number
  /** Still to reserve: requested less everything reserved (spares count, as for TS06). */
  remaining: number
  /** Serialised only: every unit and what it is doing in the window, by serial number. */
  units: UnitView[]
  /** Serialised only: free, working units in serial order (the order the server picks in). */
  freeSerials: string[]
  /** Most units (requested + spare) the row can take now: availability, and free units when serialised. */
  capacity: number
}

const overlaps = (r: EquipmentReservation, from: number, to: number) =>
  new Date(r.loanedFrom).getTime() < to && new Date(r.loanedUntil).getTime() > from

/** Serial order as the server sorts it (Java's String order, by UTF-16 code unit). */
const bySerial = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

export function buildRows({ lines, availability, eventReservations, units, kindReservations, eventId, eventStart, eventEnd, window }: {
  lines: RequestLine[]
  availability: EquipmentAvailability[]
  /** The event's own reservations (any window). */
  eventReservations: EquipmentReservation[]
  /** Every unit's status for the window (TS03 blocks). */
  units: EquipmentUnit[]
  /** Per serialised kind: every reservation overlapping the window, any event. */
  kindReservations: Record<string, EquipmentReservation[]>
  eventId: string
  eventStart: string
  eventEnd: string
  /** ISO instants of the loan window. */
  window: { from: string; to: string }
}): FulfilmentRow[] {
  const from = new Date(window.from).getTime()
  const to = new Date(window.to).getTime()
  return lines.map((line) => {
    const stock = availability.find((a) => a.equipmentId === line.equipmentId) ?? null
    const serialised = stock?.serialised ?? false
    const reserved = reservedForEventPeriod(eventReservations, line.equipmentId, eventStart, eventEnd, 'requested')
    const spareReserved = reservedForEventPeriod(eventReservations, line.equipmentId, eventStart, eventEnd, 'spare')

    let unitViews: UnitView[] = []
    if (serialised) {
      const holding = new Map<string, EquipmentReservation>()
      const overlapping = [
        ...(kindReservations[line.equipmentId] ?? []),
        ...eventReservations.filter((r) => r.equipmentId === line.equipmentId),
      ].filter((r) => r.serialNumber !== null && overlaps(r, from, to))
      // This event's own hold wins over another's (they cannot both hold it).
      overlapping.sort((a, b) => Number(a.eventId === eventId) - Number(b.eventId === eventId))
      overlapping.forEach((r) => holding.set(r.serialNumber!, r))
      unitViews = units
        .filter((u) => u.equipmentId === line.equipmentId)
        .map((u): UnitView => {
          const held = holding.get(u.serialNumber)
          if (held) {
            return {
              serialNumber: u.serialNumber,
              state: held.eventId === eventId
                ? { kind: 'here', spare: held.spare === true }
                : { kind: 'elsewhere', eventName: held.eventName ?? null },
            }
          }
          if (u.status !== 'Available') return { serialNumber: u.serialNumber, state: { kind: 'blocked', status: u.status } }
          return { serialNumber: u.serialNumber, state: { kind: 'free' } }
        })
        .sort((a, b) => bySerial(a.serialNumber, b.serialNumber))
    }
    const freeSerials = unitViews.filter((u) => u.state.kind === 'free').map((u) => u.serialNumber)
    const available = stock?.availableQuantity ?? null
    const capacity = available === null ? 0 : serialised ? Math.min(available, freeSerials.length) : available
    return {
      line,
      serialised,
      available,
      reserved,
      spareReserved,
      remaining: Math.max(0, line.quantity - reserved - spareReserved),
      units: unitViews,
      freeSerials,
      capacity,
    }
  })
}

/** What the technician has set on a row. `now` undefined = the default
 * (everything remaining that fits); `serials` null = picked automatically. */
export interface RowChoice {
  now?: number
  spare: number
  serials: string[] | null
}

export const NO_CHOICE: RowChoice = { spare: 0, serials: null }

/** Units the row reserves towards the request: the default is what remains, as far as stock allows. */
export function maxNow(row: FulfilmentRow): number {
  return Math.max(0, Math.min(row.remaining, row.capacity))
}

export function nowOf(row: FulfilmentRow, choice: RowChoice): number {
  return choice.now ?? maxNow(row)
}

/** The units a serialised row will reserve, in serial order: the chosen
 * ones, else the first free ones. The first `now` are for the request and
 * the rest are spares. */
export function unitsFor(row: FulfilmentRow, choice: RowChoice): string[] {
  if (!row.serialised) return []
  const count = nowOf(row, choice) + choice.spare
  if (choice.serials !== null) return [...choice.serials].sort(bySerial)
  return row.freeSerials.slice(0, count)
}

/** Why a row cannot be reserved as set, or null when it can. */
export function rowProblem(row: FulfilmentRow, choice: RowChoice): string | null {
  const now = nowOf(row, choice)
  if (!Number.isInteger(now) || now < 0) return 'Enter a whole number of units.'
  if (now > row.remaining) return `Only ${row.remaining} still requested. Add any extra units as spares.`
  if (now + choice.spare > row.capacity) return `Only ${row.capacity} can be reserved for this window.`
  if (row.serialised && choice.serials !== null && choice.serials.length !== now + choice.spare) {
    const missing = now + choice.spare - choice.serials.length
    return missing > 0 ? `Choose ${missing} more ${missing === 1 ? 'unit' : 'units'}.` : `Choose ${now + choice.spare} units.`
  }
  return null
}

/** The batch to send (D33): one line per row that reserves something. */
export function planBatch(rows: FulfilmentRow[], choices: Record<string, RowChoice>): BatchReserveLine[] {
  return rows.flatMap((row) => {
    const choice = choices[row.line.equipmentId] ?? NO_CHOICE
    const now = nowOf(row, choice)
    if (now + choice.spare === 0) return []
    return [{
      equipmentId: row.line.equipmentId,
      quantity: now,
      spare: choice.spare,
      ...(row.serialised ? { serialNumbers: unitsFor(row, choice) } : {}),
    }]
  })
}

/** "4", or "4 + 1 spare". */
export function describeReserved(row: FulfilmentRow): string {
  return row.spareReserved > 0 ? `${row.reserved} + ${row.spareReserved} spare` : String(row.reserved)
}

/** The event's reservations grouped by item, in request order, then any others. */
export function groupReservations(reservations: EquipmentReservation[], lines: RequestLine[]) {
  const order = new Map(lines.map((line, index) => [line.equipmentId, index]))
  const groups = new Map<string, { equipmentId: string; equipmentName: string; reservations: EquipmentReservation[] }>()
  for (const r of reservations) {
    const group = groups.get(r.equipmentId) ?? { equipmentId: r.equipmentId, equipmentName: r.equipmentName, reservations: [] }
    group.reservations.push(r)
    groups.set(r.equipmentId, group)
  }
  return [...groups.values()].sort((a, b) =>
    (order.get(a.equipmentId) ?? Infinity) - (order.get(b.equipmentId) ?? Infinity))
}
