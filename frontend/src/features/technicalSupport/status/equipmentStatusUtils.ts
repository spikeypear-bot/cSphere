import type { AvailabilityCount, EquipmentItem } from './equipmentStatus.types'

export function countAvailableByType(items: EquipmentItem[]): AvailabilityCount[] {
  const byType = new Map<string, AvailabilityCount>()

  for (const item of items) {
    const entry = byType.get(item.typeName) ?? {
      typeName: item.typeName,
      availableCount: 0,
      totalCount: 0,
    }
    entry.totalCount += 1
    if (item.status === 'Available') entry.availableCount += 1
    byType.set(item.typeName, entry)
  }

  return [...byType.values()]
}