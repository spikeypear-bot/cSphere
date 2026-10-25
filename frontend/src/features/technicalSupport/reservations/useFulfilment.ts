import { useEffect, useState } from 'react'
import { fetchEquipmentReservations } from '../inventory/inventoryApi'
import { fetchEquipmentUnits } from '../status/equipmentStatusApi'
import type { EquipmentUnit } from '../status/equipmentStatus.types'
import type {
  EquipmentAvailability,
  EquipmentReservation,
  EquipmentRequestSummary,
  RequestLine,
  TimePeriod,
} from './reservation.types'
import { fetchAvailability, fetchRequestLines, fetchReservationsForEvent, toIso } from './reservationApi'

export interface FulfilmentData {
  lines: RequestLine[]
  /** The event's own reservations. */
  reservations: EquipmentReservation[]
  /** For the loan window: availability per item, every unit's status, and
   * each serialised item's reservations by any event. */
  availability: EquipmentAvailability[]
  units: EquipmentUnit[]
  kindReservations: Record<string, EquipmentReservation[]>
}

async function load(request: EquipmentRequestSummary, period: TimePeriod): Promise<FulfilmentData> {
  const [lines, reservations, availability, units] = await Promise.all([
    fetchRequestLines(request.requestId),
    fetchReservationsForEvent(request.eventId),
    fetchAvailability(request.requestId, period),
    fetchEquipmentUnits(period),
  ])
  // Which units other events hold is only needed for serialised items.
  const serialised = availability.filter((a) => a.serialised)
  const held = await Promise.all(serialised.map((a) =>
    fetchEquipmentReservations(a.equipmentId, toIso(period.start), toIso(period.end))))
  return {
    lines,
    reservations,
    availability,
    units,
    kindReservations: Object.fromEntries(serialised.map((a, i) => [a.equipmentId, held[i]])),
  }
}

/** TS01 + TS02: everything the request page needs for one loan window,
 * reloaded when the window or `reloadKey` changes. While a reload runs the
 * previous figures stay on screen but `loaded` is false, so nothing (a
 * reservation, the TS06 decision) acts on figures that may be stale. */
export function useFulfilment(request: EquipmentRequestSummary, period: TimePeriod, periodValid: boolean, reloadKey: number) {
  const key = JSON.stringify([request.requestId, period.start, period.end, reloadKey])
  const [state, setState] = useState<{ key: string; data: FulfilmentData } | null>(null)
  const [failed, setFailed] = useState<{ key: string; attempt: number } | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!periodValid) return
    let cancelled = false
    load(request, period)
      .then((data) => { if (!cancelled) setState({ key, data }) })
      .catch(() => { if (!cancelled) setFailed({ key, attempt }) })
    return () => { cancelled = true }
    // `key` covers the request and the window.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, attempt, periodValid])

  return {
    data: state?.data ?? null,
    loaded: periodValid && state?.key === key,
    error: failed?.key === key && failed.attempt === attempt,
    retry: () => setAttempt((n) => n + 1),
  }
}
