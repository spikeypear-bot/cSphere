export type EquipmentStatus = 'Available' | 'Faulty' | 'Unavailable'
export const EQUIPMENT_STATUSES: EquipmentStatus[] = ['Available', 'Faulty', 'Unavailable']
export type BlockStatus = Exclude<EquipmentStatus, 'Available'>

export interface EquipmentUnit {
  equipmentId: string
  equipmentName: string
  serialNumber: string
  status: EquipmentStatus // status for the viewed period
}

// A saved Faulty/Unavailable block. end === null means "no end date".
export interface StatusPeriod {
  id: string
  status: BlockStatus
  start: string
  end: string | null
}

// Values from <input type="datetime-local">, e.g. "2026-09-25T09:00"
export interface TimePeriod {
  start: string
  end: string
}

export interface AvailabilityCount {
  typeName: string
  availableCount: number
  totalCount: number
}