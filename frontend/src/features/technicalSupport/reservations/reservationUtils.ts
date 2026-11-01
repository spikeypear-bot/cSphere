import { ApiError } from './reservationApi'
import type { EquipmentReservation, RequestLine } from './reservation.types'

/** TS06: how many units of a kind are reserved for the event's whole period.
 * A reservation that starts late or ends early does not count. `which`
 * keeps only the requested units or only the spares (D33); spares count
 * towards approval, so the default is both. */
export function reservedForEventPeriod(
  reservations: EquipmentReservation[],
  equipmentId: string,
  eventStart: string,
  eventEnd: string,
  which: 'all' | 'requested' | 'spare' = 'all',
): number {
  const start = new Date(eventStart).getTime()
  const end = new Date(eventEnd).getTime()
  return reservations
    .filter((reservation) => reservation.equipmentId === equipmentId
      && (which === 'all' || (reservation.spare === true) === (which === 'spare'))
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
      return error.body || 'That equipment could not be found.'
    }
  }
  return 'Could not save the reservations. Please try again.'
}

/** An instant as a datetime-local value in Singapore time (the inverse of toIso). */
export function toLocalInputValue(iso: string): string {
  return new Date(new Date(iso).getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)
}
