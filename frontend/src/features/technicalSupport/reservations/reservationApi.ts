import type {
  EquipmentAvailability,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
  TimePeriod,
} from './reservation.types'
import { apiClient, ApiClientError } from '../../../lib/apiClient'

export class ApiError extends Error {
  status: number
  body: string
  constructor(status: number, body: string) {
    super(`Request failed with status ${status}`)
    this.status = status
    this.body = body
  }
}

async function call<T>(request: () => Promise<T>): Promise<T> {
  try {
    return await request()
  } catch (error) {
    if (error instanceof ApiClientError) {
      throw new ApiError(error.status, error.message)
    }
    throw error
  }
}

// "2026-09-25T09:00" (local time) -> "2026-09-25T01:00:00.000Z"
function toIso(localValue: string): string {
  return new Date(localValue).toISOString()
}

// AC 1
export function fetchProcessingRequests(): Promise<EquipmentRequestSummary[]> {
  return apiClient.get<EquipmentRequestSummary[]>('/equipment-requests/processing')
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

// AC 5-9
export function reserveEquipment(
  eventId: string,
  equipmentId: string,
  quantity: number,
  serialNumber: string | null,
  period: TimePeriod,
): Promise<EquipmentReservation> {
  return call(() => apiClient.post<EquipmentReservation>('/equipment/reservations', {
      eventId,
      equipmentId,
      quantity,
      serialNumber,
      start: toIso(period.start),
      end: toIso(period.end),
    }))
}

// AC 11
export function fetchReservationsForEvent(eventId: string): Promise<EquipmentReservation[]> {
  return apiClient.get<EquipmentReservation[]>(`/equipment/events/${eventId}/reservations`)
}