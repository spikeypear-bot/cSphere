import { ApiError } from './reservationApi'
import type { EquipmentReservation, RequestLine } from './reservation.types'

/** TS06: how many units of a kind are reserved for the event's whole period.
 * A reservation that starts late or ends early does not count. */
export function reservedForEventPeriod(
  reservations: EquipmentReservation[],
  equipmentId: string,
  eventStart: string,
  eventEnd: string,
): number {
  const start = new Date(eventStart).getTime()
  const end = new Date(eventEnd).getTime()
  return reservations
    .filter((reservation) => reservation.equipmentId === equipmentId
      && new Date(reservation.loanedFrom).getTime() <= start
      && new Date(reservation.loanedUntil).getTime() >= end)
    .reduce((total, reservation) => total + reservation.quantity, 0)
}

/** TS06: approval needs every requested quantity reserved for the whole event. */
export function everyLineReserved(
  lines: RequestLine[],
  reservations: EquipmentReservation[],
  eventStart: string,
  eventEnd: string,
): boolean {
  return lines.every((line) =>
    reservedForEventPeriod(reservations, line.equipmentId, eventStart, eventEnd) >= line.quantity)
}

/** "2 × Projector, 1 × Microphone" — a request's items on one line. */
export function summariseItems(items: RequestLine[] | undefined): string {
  if (!items || items.length === 0) return 'No itemised equipment'
  return items.map((item) => `${item.quantity} × ${item.equipmentName}`).join(', ')
}

// Turns a raw error into the message shown to the user (AC 9).
export function describeReserveError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return error.body || 'That equipment is not available for the selected period.'
    }
    if (error.status === 400) {
      return error.body || 'That reservation request was invalid.'
    }
    if (error.status === 404) {
      return 'That equipment could not be found.'
    }
  }
  return 'Could not save the reservation. Please try again.'
}

  export function formatMoment(iso: string): string {
    return new Date(iso).toLocaleString('en-SG', { dateStyle: 'medium', timeStyle: 'short' })
  }

export function describeReservation(from: string, until: string): string {
  return `${formatMoment(from)} to ${formatMoment(until)}`
}

export function toLocalInputValue(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}