import type {
  BlockStatus,
  EquipmentUnit,
  StatusPeriod,
  TimePeriod,
} from './equipmentStatus.types'

// Empty because of the Vite proxy we set up for CORS.
const API_BASE = ''

// Lets the page react to specific failures, like 409 (conflict).
export class ApiError extends Error {
  status: number
  constructor(status: number) {
    super(`Request failed with status ${status}`)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) throw new ApiError(response.status)
  if (response.status === 204) return undefined as T // DELETE returns no body
  return response.json() as Promise<T>
}

// "2026-09-25T09:00" (local time) -> exact moment "2026-09-25T01:00:00.000Z"
function toIso(localValue: string): string {
  return new Date(localValue).toISOString()
}

function unitPath(equipmentId: string, serialNumber: string): string {
  return `/api/equipment/${equipmentId}/units/${encodeURIComponent(serialNumber)}`
}

export function fetchEquipmentUnits(period: TimePeriod): Promise<EquipmentUnit[]> {
  const query = `start=${encodeURIComponent(toIso(period.start))}&end=${encodeURIComponent(toIso(period.end))}`
  return request<EquipmentUnit[]>(`/api/equipment/units?${query}`)
}

export function fetchStatusPeriods(
  equipmentId: string,
  serialNumber: string,
): Promise<StatusPeriod[]> {
  return request<StatusPeriod[]>(`${unitPath(equipmentId, serialNumber)}/periods`)
}

export function addStatusPeriod(
  equipmentId: string,
  serialNumber: string,
  status: BlockStatus,
  start: string,
  end: string | null, // null = no end date
): Promise<StatusPeriod> {
  return request<StatusPeriod>(`${unitPath(equipmentId, serialNumber)}/periods`, {
    method: 'POST',
    body: JSON.stringify({
      status,
      start: toIso(start),
      end: end === null ? null : toIso(end),
    }),
  })
}

export function removeStatusPeriod(periodId: string): Promise<void> {
  return request<void>(`/api/equipment/periods/${periodId}`, { method: 'DELETE' })
}