import type { ApiError } from './reservationApi'

// Turns a raw error into the message shown to the user (AC 9).
export function describeReserveError(error: unknown): string {
  if (error && typeof error === 'object' && 'status' in error) {
    const apiError = error as ApiError
    if (apiError.status === 409) {
      // The backend already writes a specific, readable message for 409s.
      return apiError.body || 'That equipment is not available for the selected period.'
    }
    if (apiError.status === 400) {
      return apiError.body || 'That reservation request was invalid.'
    }
    if (apiError.status === 404) {
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