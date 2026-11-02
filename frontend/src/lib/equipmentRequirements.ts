import type { EquipmentOption } from '../components/equipment/EquipmentPicker'

const EQUIPMENT_ITEMS_HEADING = 'Equipment items:'
const EQUIPMENT_NOTES_HEADING = 'Additional notes:'

export function equipmentRequirementsFrom(
  selected: Record<string, string>,
  notes: string,
  catalogue: EquipmentOption[],
): string | null {
  const lines = catalogue.flatMap((equipment) => {
    const quantity = selected[equipment.equipmentId]
    return quantity === undefined ? [] : [`${quantity} × ${equipment.equipmentName}`]
  })
  const sections = [
    lines.length ? `${EQUIPMENT_ITEMS_HEADING}\n${lines.join('\n')}` : '',
    notes.trim() ? `${EQUIPMENT_NOTES_HEADING}\n${notes.trim()}` : '',
  ].filter(Boolean)
  return sections.length ? sections.join('\n\n') : null
}

export function equipmentDraftFrom(
  value: string | null,
  catalogue: EquipmentOption[],
): { selected: Record<string, string>; notes: string } {
  if (value?.startsWith(`${EQUIPMENT_NOTES_HEADING}\n`)) {
    return { selected: {}, notes: value.slice(EQUIPMENT_NOTES_HEADING.length + 1) }
  }
  if (!value?.startsWith(`${EQUIPMENT_ITEMS_HEADING}\n`)) {
    return { selected: {}, notes: value ?? '' }
  }

  const [itemsSection, ...notesSections] = value.split(`\n\n${EQUIPMENT_NOTES_HEADING}\n`)
  const selected: Record<string, string> = {}
  const lines = itemsSection.slice(EQUIPMENT_ITEMS_HEADING.length).trim().split('\n').filter(Boolean)
  for (const line of lines) {
    const match = /^(\d+)\s+×\s+(.+)$/.exec(line)
    const equipment = match && catalogue.find((item) => item.equipmentName === match[2])
    if (match && equipment) selected[equipment.equipmentId] = match[1]
    // Missing catalogue entries must not turn generated equipment lines into notes.
  }
  return { selected, notes: notesSections.join(`\n\n${EQUIPMENT_NOTES_HEADING}\n`) }
}

