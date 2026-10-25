export type EquipmentStatus = 'Available' | 'Faulty' | 'Unavailable'
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

/** D32: a block with the unit it is on (GET /api/equipment/status-periods). */
export interface UnitStatusPeriod extends StatusPeriod {
  equipmentId: string
  equipmentName: string
  serialNumber: string
}

// Values from <input type="datetime-local">, e.g. "2026-09-25T09:00"
export interface TimePeriod {
  start: string
  end: string
}
