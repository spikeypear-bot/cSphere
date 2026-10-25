import type {
  BatchReserveLine,
  EquipmentAvailability,
  EquipmentRequestDecision,
  EquipmentReservation,
  EquipmentRequestFilter,
  EquipmentRequestSummary,
  RequestLine,
  TimePeriod,
} from './reservation.types'
import { apiClient, ApiClientError } from '../../../lib/apiClient'

export class ApiError extends Error {
  status: number
  body: string
  /** D33: the item a reservation was refused for, when the server names one. */
  equipmentId: string | null
  constructor(status: number, body: string, equipmentId: string | null = null) {
    super(`Request failed with status ${status}`)
    this.status = status
    this.body = body
    this.equipmentId = equipmentId
  }
}

async function call<T>(request: () => Promise<T>): Promise<T> {
  try {
    return await request()
  } catch (error) {
    if (error instanceof ApiClientError) {
      throw new ApiError(error.status, error.message, error.equipmentId)
    }
    throw error
  }
}

// "2026-09-25T09:00" (local time) -> "2026-09-25T01:00:00.000Z"
export function toIso(localValue: string): string {
  return new Date(localValue).toISOString()
}

/** D32: requests by status, soonest event first (the server's order). */
export function fetchEquipmentRequests(status: EquipmentRequestFilter): Promise<EquipmentRequestSummary[]> {
  return apiClient.get<EquipmentRequestSummary[]>(`/equipment-requests?status=${status}`)
}

// AC 2
export function fetchRequestLines(requestId: string): Promise<RequestLine[]> {
  return apiClient.get<RequestLine[]>(`/equipment-requests/${requestId}/lines`)
}

// AC 3 + 4
export function fetchAvailability(
  requestId: string,
  period: TimePeriod,
): Promise<EquipmentAvailability[]> {
  const query = `start=${encodeURIComponent(toIso(period.start))}&end=${encodeURIComponent(toIso(period.end))}`
  return apiClient.get<EquipmentAvailability[]>(`/equipment/requests/${requestId}/availability?${query}`)
}

/** D33 (TS02 AC5-9): reserve several items for one window, all or nothing. */
export function reserveBatch(
  eventId: string,
  period: TimePeriod,
  lines: BatchReserveLine[],
): Promise<EquipmentReservation[]> {
  return call(() => apiClient.post<EquipmentReservation[]>('/equipment/reservations/batch', {
    eventId,
    start: toIso(period.start),
    end: toIso(period.end),
    lines,
  }))
}

// AC 11
export function fetchReservationsForEvent(eventId: string): Promise<EquipmentReservation[]> {
  return apiClient.get<EquipmentReservation[]>(`/equipment/events/${eventId}/reservations`)
}

export function updateEquipmentRequestStatus(
  requestId: string,
  status: 'approved' | 'rejected',
  rejectReason?: string,
): Promise<EquipmentRequestDecision> {
  return apiClient.patch<EquipmentRequestDecision>(
    `/equipment-requests/${requestId}/status`,
    { status, rejectReason },
  )
}
