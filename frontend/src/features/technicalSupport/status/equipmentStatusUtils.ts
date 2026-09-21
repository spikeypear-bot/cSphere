import type { AvailabilityCount, EquipmentUnit } from './equipmentStatus.types'

// A unit has no single id, so we join its two identifying parts into one string.
export function unitKey(unit: EquipmentUnit): string {
  return `${unit.equipmentId}:${unit.serialNumber}`
}

// Counts are recalculated from the list every time, never stored separately.
export function countAvailableByType(units: EquipmentUnit[]): AvailabilityCount[] {
  const byType = new Map<string, AvailabilityCount>()

  for (const unit of units) {
    const entry = byType.get(unit.equipmentName) ?? {
      typeName: unit.equipmentName,
      availableCount: 0,
      totalCount: 0,
    }
    entry.totalCount += 1
    if (unit.status === 'Available') entry.availableCount += 1
    byType.set(unit.equipmentName, entry)
  }

  return [...byType.values()]
}