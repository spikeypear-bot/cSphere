import type { ReactNode } from 'react'
import { Card } from '../../../components/ui/Card'
import { EquipmentPhoto } from '../damage/EquipmentPhoto'
import '../damage/equipmentDamage.css'

/** The photo-and-details header shown wherever one equipment kind is the
 * focus of the page: the inventory's equipment detail, a damage report's
 * detail, and the chosen equipment on the Report a problem form. */
export function EquipmentSummaryCard({ name, children }: { name: string; children: ReactNode }) {
  return (
    <Card className="equipment-summary">
      <EquipmentPhoto name={name} />
      {children}
    </Card>
  )
}
