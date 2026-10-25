import { formatEventTimeRange } from '../../venueStaff/formatEventDateTime'
import {
  EQUIPMENT_REQUEST_FILTER_LABELS,
  type EquipmentRequestSummary,
} from '../reservations/reservation.types'
import { summariseItems } from '../reservations/reservationUtils'
import { requesterName } from '../shared/format'

/** The status badge, plus Shortfall for an additional request (TS09 AC19). */
export function RequestBadges({ request }: { request: EquipmentRequestSummary }) {
  return (
    <>
      <span className={`tech-badge${request.status === 'rejected' ? ' tech-badge--danger' : ''}`}>
        {EQUIPMENT_REQUEST_FILTER_LABELS[request.status]}
      </span>
      {request.isShortfallRequest ? ' ' : null}
      {request.isShortfallRequest ? <span className="tech-badge tech-badge--danger">Shortfall</span> : null}
    </>
  )
}

/** Dates, organisation, requester and items of one request. `submittedAt`
 * is never stored yet (always null), so it is not shown. */
export function RequestFacts({ request }: { request: EquipmentRequestSummary }) {
  return (
    <dl className="tech-figures tech-facts">
      <div><dt>Event dates</dt><dd>{formatEventTimeRange(request.eventStart, request.eventEnd)}</dd></div>
      <div><dt>Organisation</dt><dd>{request.organisationName ?? 'Not recorded'}</dd></div>
      <div><dt>Requested by</dt><dd>{requesterName(request.requestedBy)}</dd></div>
      <div><dt>Items</dt><dd>{summariseItems(request.items)}</dd></div>
    </dl>
  )
}
