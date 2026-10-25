// Mirrors backend/src/main/java/com/example/connect_sphere/equipment/
// (EquipmentCatalogueResponse, EquipmentDetailResponse) for the console (D32).
import type { CatalogueItem } from '../damage/equipmentDamage.types'
import type { EquipmentStatus, StatusPeriod } from '../status/equipmentStatus.types'

/** A catalogue kind for a window: the quantity fields only come back when
 * `from` and `to` are both sent. */
export interface CatalogueWindowItem extends CatalogueItem {
  reservedQuantity: number
  outOfActionQuantity: number
  availableQuantity: number
}

export interface EquipmentDetail {
  equipmentId: string
  equipmentName: string
  serialised: boolean
  totalQuantity: number
  /** By serial number; empty for a kind not tracked by unit. */
  units: {
    serialNumber: string
    /** Its status now. */
    status: EquipmentStatus
    /** All its TS03 blocks, earliest first. */
    statusPeriods: StatusPeriod[]
  }[]
}
