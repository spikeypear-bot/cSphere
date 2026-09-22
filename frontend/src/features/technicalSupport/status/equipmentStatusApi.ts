import { apiClient, ApiClientError } from '../../../lib/apiClient'
import type {
  BlockStatus,
  EquipmentUnit,
  StatusPeriod,
  TimePeriod,
} from './equipmentStatus.types'

// Lets the page react to specific failures, like 409 (conflict).
export class ApiError extends Error {
  status: number
  constructor(status: number) {
    super(`Request failed with status ${status}`)
    this.status = status
  }
}

// Calls go through apiClient rather than a bare fetch so they carry the access
// token, renew it when it expires mid-session, and end the session on a dead
// one — behaviour every other feature already gets for free. This file used
// its own fetch because it was written before login existed (D19); without
// this, every equipment call would 401. ApiClientError is translated back to
// the ApiError the page already checks for 409 on.
async function call<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run()
  } catch (caught) {
    if (caught instanceof ApiClientError) throw new ApiError(caught.status)
    throw caught
  }
}

// apiClient prefixes /api itself (VITE_API_BASE_URL), so paths start below it.
const EQUIPMENT = '/equipment'

// "2026-09-25T09:00" (local time) -> exact moment "2026-09-25T01:00:00.000Z"
function toIso(localValue: string): string {
  return new Date(localValue).toISOString()
}

function unitPath(equipmentId: string, serialNumber: string): string {
  return `${EQUIPMENT}/${equipmentId}/units/${encodeURIComponent(serialNumber)}`
}

export function fetchEquipmentUnits(period: TimePeriod): Promise<EquipmentUnit[]> {
  const query = `start=${encodeURIComponent(toIso(period.start))}&end=${encodeURIComponent(toIso(period.end))}`
  return call(() => apiClient.get<EquipmentUnit[]>(`${EQUIPMENT}/units?${query}`))
}

export function fetchStatusPeriods(
  equipmentId: string,
  serialNumber: string,
): Promise<StatusPeriod[]> {
  return call(() => apiClient.get<StatusPeriod[]>(`${unitPath(equipmentId, serialNumber)}/periods`))
}

export function addStatusPeriod(
  equipmentId: string,
  serialNumber: string,
  status: BlockStatus,
  start: string,
  end: string | null, // null = no end date
): Promise<StatusPeriod> {
  return call(() =>
    apiClient.post<StatusPeriod>(`${unitPath(equipmentId, serialNumber)}/periods`, {
      status,
      start: toIso(start),
      end: end === null ? null : toIso(end),
    }),
  )
}

export function removeStatusPeriod(periodId: string): Promise<void> {
  return call(() => apiClient.del<void>(`${EQUIPMENT}/periods/${periodId}`))
}