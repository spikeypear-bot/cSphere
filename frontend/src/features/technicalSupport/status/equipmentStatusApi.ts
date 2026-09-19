import type { EquipmentItem, EquipmentStatus, TimePeriod } from './equipmentStatus.types'

// Our pretend database. It resets whenever you refresh the page.
const items: EquipmentItem[] = [
  { id: 'p1', name: 'Projector 1', typeName: 'Projector', status: 'Available' },
  { id: 'p2', name: 'Projector 2', typeName: 'Projector', status: 'Available' },
  { id: 'p3', name: 'Projector 3', typeName: 'Projector', status: 'Faulty' },
  { id: 'm1', name: 'Microphone 1', typeName: 'Microphone', status: 'Available' },
  { id: 'm2', name: 'Microphone 2', typeName: 'Microphone', status: 'Unavailable' },
]

const delay = (ms = 300) => new Promise((resolve) => setTimeout(resolve, ms))

// The underscore tells TypeScript "I know this is unused for now".
// The real backend will use the period; the mock ignores it.
export async function fetchEquipment(_period: TimePeriod): Promise<EquipmentItem[]> {
  await delay()
  return items.map((item) => ({ ...item })) // return copies, not the originals
}

export async function saveEquipmentStatus(
  id: string,
  status: EquipmentStatus,
): Promise<EquipmentItem> {
  await delay()
  const item = items.find((i) => i.id === id)
  if (!item) throw new Error('Equipment not found')
  item.status = status
  return { ...item }
}