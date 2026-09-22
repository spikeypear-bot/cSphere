import type {
  EquipmentAvailability,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
  TimePeriod,
} from './reservation.types'

// Empty because of the Vite proxy set up for TS03 (proxies /api to :8080).
const API_BASE = ''

export class ApiError extends Error {
  status: number
  body: string
  constructor(status: number, body: string) {
    super(`Request failed with status ${status}`)
    this.status = status
    this.body = body
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new ApiError(response.status, body)
  }
  return response.json() as Promise<T>
}

// "2026-09-25T09:00" (local time) -> "2026-09-25T01:00:00.000Z"
function toIso(localValue: string): string {
  return new Date(localValue).toISOString()
}

// AC 1
export function fetchProcessingRequests(): Promise<EquipmentRequestSummary[]> {
  return request<EquipmentRequestSummary[]>('/api/equipment-requests/processing')
}

// AC 2
export function fetchRequestLines(requestId: string): Promise<RequestLine[]> {
  return request<RequestLine[]>(`/api/equipment-requests/${requestId}/lines`)
}

// AC 3 + 4
export function fetchAvailability(
  requestId: string,
  period: TimePeriod,
): Promise<EquipmentAvailability[]> {
  const query = `start=${encodeURIComponent(toIso(period.start))}&end=${encodeURIComponent(toIso(period.end))}`
  return request<EquipmentAvailability[]>(
    `/api/equipment/requests/${requestId}/availability?${query}`,
  )
}

// AC 5-9
export function reserveEquipment(
  eventId: string,
  equipmentId: string,
  quantity: number,
  serialNumber: string | null,
  period: TimePeriod,
): Promise<EquipmentReservation> {
  return request<EquipmentReservation>('/api/equipment/reservations', {
    method: 'POST',
    body: JSON.stringify({
      eventId,
      equipmentId,
      quantity,
      serialNumber,
      start: toIso(period.start),
      end: toIso(period.end),
    }),
  })
}

// AC 11
export function fetchReservationsForEvent(eventId: string): Promise<EquipmentReservation[]> {
  return request<EquipmentReservation[]>(`/api/equipment/events/${eventId}/reservations`)
}