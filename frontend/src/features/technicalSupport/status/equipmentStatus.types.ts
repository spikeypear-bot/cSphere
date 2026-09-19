export type EquipmentStatus = 'Available' | 'Faulty' | 'Unavailable'

export const EQUIPMENT_STATUSES: EquipmentStatus[] = ['Available', 'Faulty', 'Unavailable']

export interface EquipmentItem {
  id: string
  name: string
  typeName: string
  status: EquipmentStatus
}

export interface TimePeriod {
  start: string
  end: string
}

export interface AvailabilityCount {
  typeName: string
  availableCount: number
  totalCount: number
}