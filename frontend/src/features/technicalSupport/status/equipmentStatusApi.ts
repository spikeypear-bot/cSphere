import type { EquipmentStatus, EquipmentUnit, TimePeriod } from './equipmentStatus.types'

// The address of the backend. We may change this one line later (see the CORS section).
const API_BASE = ''

// One helper so both calls handle errors the same way.
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`)
  }
  return response.json() as Promise<T>
}

// The backend doesn't filter by period yet, so it's accepted but ignored.
export function fetchEquipmentUnits(_period: TimePeriod): Promise<EquipmentUnit[]> {
  return request<EquipmentUnit[]>('/api/equipment/units')
}

export function saveUnitStatus(
  unit: EquipmentUnit,
  status: EquipmentStatus,
): Promise<EquipmentUnit> {
  // encodeURIComponent stops odd characters in a serial number from breaking the URL.
  const path = `/api/equipment/${unit.equipmentId}/units/${encodeURIComponent(unit.serialNumber)}/status`
  return request<EquipmentUnit>(path, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}