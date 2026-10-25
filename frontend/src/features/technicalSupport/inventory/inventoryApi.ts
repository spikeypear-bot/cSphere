import { apiClient } from '../../../lib/apiClient'
import type { EquipmentReservation } from '../reservations/reservation.types'
import type { CatalogueWindowItem, EquipmentDetail } from './inventory.types'

// D32 console reads. apiClient prefixes /api itself. `from`/`to` are ISO
// instants of a half-open window.
const windowQuery = (from: string, to: string) => `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`

export const fetchCatalogueForWindow = (from: string, to: string) =>
  apiClient.get<CatalogueWindowItem[]>(`/equipment/catalogue?${windowQuery(from, to)}`)

export const fetchEquipmentDetail = (equipmentId: string) =>
  apiClient.get<EquipmentDetail>(`/equipment/${equipmentId}`)

export const fetchEquipmentReservations = (equipmentId: string, from: string, to: string) =>
  apiClient.get<EquipmentReservation[]>(`/equipment/${equipmentId}/reservations?${windowQuery(from, to)}`)
