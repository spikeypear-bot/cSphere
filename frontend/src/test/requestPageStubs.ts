import { jsonResponse } from './apiStubs'
import { toLocalInputValue } from '../features/technicalSupport/reservations/reservationUtils'

/** The loan window the request page starts with (the event's times), as
 * the page sends it: local input values turned back into ISO instants. */
export function eventWindow(eventStart: string, eventEnd: string) {
  const from = new Date(toLocalInputValue(eventStart)).toISOString()
  const to = new Date(toLocalInputValue(eventEnd)).toISOString()
  return {
    from,
    to,
    startEnd: `start=${encodeURIComponent(from)}&end=${encodeURIComponent(to)}`,
    fromTo: `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  }
}

interface Request { requestId: string; eventId: string; eventStart: string; eventEnd: string }

/** Route table for the request page's reads (TS01/TS02/TS06, D33). Values
 * may be functions so a test can change them after a reservation. Every
 * serialised kind in `availability` gets its by-kind reservations read. */
export function requestPageRoutes(request: Request, data: {
  requests: () => unknown[]
  lines: () => unknown[]
  reservations: () => unknown[]
  availability: () => { equipmentId: string; serialised: boolean }[]
  units?: () => unknown[]
  kindReservations?: (equipmentId: string) => unknown[]
}): Record<string, (body: unknown) => Response> {
  const w = eventWindow(request.eventStart, request.eventEnd)
  const routes: Record<string, (body: unknown) => Response> = {
    'GET /api/equipment-requests?status=all': () => jsonResponse(200, data.requests()),
    [`GET /api/equipment-requests/${request.requestId}/lines`]: () => jsonResponse(200, data.lines()),
    [`GET /api/equipment/events/${request.eventId}/reservations`]: () => jsonResponse(200, data.reservations()),
    [`GET /api/equipment/requests/${request.requestId}/availability?${w.startEnd}`]: () => jsonResponse(200, data.availability()),
    [`GET /api/equipment/units?${w.startEnd}`]: () => jsonResponse(200, data.units?.() ?? []),
  }
  for (const kind of data.availability().filter((a) => a.serialised)) {
    routes[`GET /api/equipment/${kind.equipmentId}/reservations?${w.fromTo}`] =
      () => jsonResponse(200, data.kindReservations?.(kind.equipmentId) ?? [])
  }
  return routes
}
