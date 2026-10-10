import type { EquipmentAvailability, RequestLine } from '../reservations/reservation.types'
import type { EquipmentUnit } from '../status/equipmentStatus.types'

export interface EquipmentReviewItem {
  line: RequestLine
  availability: EquipmentAvailability | null
  units: EquipmentUnit[]
  suitableQuantity: number
  sufficient: boolean
  suitable: boolean
  canFulfill: boolean
}

export function assessEquipmentReview(
  lines: RequestLine[],
  availability: EquipmentAvailability[],
  units: EquipmentUnit[],
): EquipmentReviewItem[] {
  return lines.map((line) => {
    const stock = availability.find((item) => item.equipmentId === line.equipmentId) ?? null
    const equipmentUnits = units.filter((unit) => unit.equipmentId === line.equipmentId)
    const suitableQuantity = stock?.serialised
      ? equipmentUnits.filter((unit) => unit.status === 'Available').length
      : stock?.availableQuantity ?? 0
    const sufficient = stock !== null && stock.availableQuantity >= line.quantity
    const suitable = stock !== null && suitableQuantity >= line.quantity

    return {
      line,
      availability: stock,
      units: equipmentUnits,
      suitableQuantity,
      sufficient,
      suitable,
      canFulfill: sufficient && suitable,
    }
  })
}
