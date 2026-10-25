import { useEffect, useState } from 'react'
import type { EquipmentReservation, EquipmentRequestSummary, RequestLine } from './reservation.types'
import { fetchRequestLines, fetchReservationsForEvent } from './reservationApi'

interface Planning {
  requestId: string
  key: string
  lines: RequestLine[]
  reservations: EquipmentReservation[]
}

/** TS02 AC2 + AC11: a request's lines and its event's saved reservations,
 * reloaded whenever `reloadKey` changes. While a reload is in flight the
 * previous figures stay on screen but `loaded` is false, so nothing (the
 * TS06 decision) acts on figures that may be stale. */
export function useRequestPlanning(request: EquipmentRequestSummary, reloadKey: number) {
  const [planning, setPlanning] = useState<Planning | null>(null)
  const [failedKey, setFailedKey] = useState<string | null>(null)
  const key = `${request.requestId}#${reloadKey}`

  useEffect(() => {
    let cancelled = false
    Promise.all([
      fetchRequestLines(request.requestId),
      fetchReservationsForEvent(request.eventId),
    ])
      .then(([lines, reservations]) => {
        if (!cancelled) setPlanning({ requestId: request.requestId, key, lines, reservations })
      })
      .catch(() => {
        if (!cancelled) setFailedKey(key)
      })
    return () => { cancelled = true }
  }, [key, request.requestId, request.eventId])

  const mine = planning?.requestId === request.requestId ? planning : null
  return {
    lines: mine?.lines ?? [],
    reservations: mine?.reservations ?? [],
    loaded: mine?.key === key,
    error: failedKey === key ? 'Could not load equipment requirements or existing reservations.' : null,
  }
}
