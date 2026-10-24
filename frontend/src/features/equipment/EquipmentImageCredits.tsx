import { EQUIPMENT_MEDIA } from './equipmentCatalogueMedia'

interface Props {
  /** Only credit these catalogue names; omit to credit every photo. */
  names?: readonly string[]
}

// Attribution for the Wikimedia Commons equipment photos (TS09). Most of the
// licences (CC BY, CC BY-SA) require the author, licence and source to be shown
// alongside the image.
export function EquipmentImageCredits({ names }: Props) {
  const entries = Object.entries(EQUIPMENT_MEDIA).filter(([name]) => !names || names.includes(name))
  if (entries.length === 0) return null

  return (
    <section className="equipment-image-credits" aria-label="Image credits">
      <h2>Image credits</h2>
      <ul>
        {entries.map(([name, { credit }]) => (
          <li key={name}>
            {name}: <a href={credit.sourceUrl} target="_blank" rel="noreferrer">{credit.title}</a> by{' '}
            {credit.author},{' '}
            {credit.licenseUrl ? (
              <a href={credit.licenseUrl} target="_blank" rel="noreferrer">{credit.license}</a>
            ) : (
              credit.license
            )}
            , via Wikimedia Commons
          </li>
        ))}
      </ul>
    </section>
  )
}
