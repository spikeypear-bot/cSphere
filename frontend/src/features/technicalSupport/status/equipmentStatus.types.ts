export type EquipmentStatus = 'Available' | 'Faulty' | 'Unavailable'

export const EQUIPMENT_STATUSES: EquipmentStatus[] = ['Available', 'Faulty', 'Unavailable']

// One physical unit, exactly as the backend sends it.
export interface EquipmentUnit {
  equipmentId: string
  equipmentName: string   // "Projector"
  serialNumber: string    // "PJ-001"
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