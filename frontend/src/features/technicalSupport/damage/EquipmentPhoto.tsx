import { equipmentMediaFor } from '../../equipment/equipmentCatalogueMedia'

/** The catalogue photo for an equipment kind, or a lettered placeholder when
 * there is none (iPad Floor Stand, Badge Printer Cards). */
export function EquipmentPhoto({ name }: { name: string }) {
  const media = equipmentMediaFor(name)
  if (!media) {
    return (
      <div className="equipment-damage__photo equipment-damage__photo--placeholder" role="img" aria-label={`No photo of ${name}`}>
        {name.charAt(0).toUpperCase()}
      </div>
    )
  }
  return <img className="equipment-damage__photo" src={media.image} alt={name} />
}
