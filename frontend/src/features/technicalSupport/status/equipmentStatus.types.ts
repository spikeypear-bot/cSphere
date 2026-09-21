export type EquipmentStatus = 'Available' | 'Faulty' | 'Unavailable'

// Only these two can be saved as a block. "Available" is the default state.
export type BlockStatus = 'Faulty' | 'Unavailable'
export const BLOCK_STATUSES: BlockStatus[] = ['Faulty', 'Unavailable']

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