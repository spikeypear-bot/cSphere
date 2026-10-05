import { ApiError } from './reservationApi'

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