import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { DecisionCard } from '../reservations/DecisionCard'
import { buildRows } from '../reservations/fulfilment'
import { ReserveCard } from '../reservations/ReserveCard'
import type { EquipmentRequestStatus, EquipmentRequestSummary, TimePeriod } from '../reservations/reservation.types'
import { fetchEquipmentRequests, toIso } from '../reservations/reservationApi'
import { toLocalInputValue } from '../reservations/reservationUtils'
import { useFulfilment } from '../reservations/useFulfilment'
import { TS } from '../shared/faultyEquipment'
import { describeWhen, requesterName } from '../shared/format'
import { ResourceView } from '../shared/ResourceView'
import { useResource } from '../shared/useResource'
import { RequestBadges } from './RequestFacts'
import '../technicalSupport.css'
import '../reservations/fulfilment.css'

/** One equipment request on one page: a compact summary, the table that
 * reserves its equipment (TS01 availability, TS02, D33) and TS06's decision. */
export function EquipmentRequestDetailPage() {
  const { requestId = '' } = useParams<{ requestId: string }>()
  // There is no single-request read: the "all" list is the same shape and
  // already scoped to Technical Support (D32).
  const requests = useResource(() => fetchEquipmentRequests('all'), requestId)
  const [decided, setDecided] = useState<{ requestId: string; status: EquipmentRequestStatus; reason: string | null } | null>(null)

  return (
    <ResourceView resource={requests} loadingText="Loading equipment request…"
      errorText="The equipment request could not be loaded."
      emptyText="This equipment request could not be found. It may belong to an event that was cancelled or has finished."
      isEmpty={(list) => !list.some((r) => r.requestId === requestId)}>
      {(list) => {
        const found = list.find((r) => r.requestId === requestId)!
        const mine = decided?.requestId === requestId ? decided : null
        const request = mine ? { ...found, status: mine.status } : found
        return (
          <RequestWorkspace
            key={request.requestId}
            request={request}
            rejectReason={mine?.reason ?? null}
            onDecided={(status, reason) => setDecided({ requestId, status, reason })}
          />
        )
      }}
    </ResourceView>
  )
}

function RequestWorkspace({ request, rejectReason, onDecided }: {
  request: EquipmentRequestSummary
  rejectReason: string | null
  onDecided: (status: EquipmentRequestStatus, reason: string | null) => void
}) {
  // Bumped after a reservation or a decision: the figures reload.
  const [reloadKey, setReloadKey] = useState(0)
  const [period, setPeriod] = useState<TimePeriod>(() => ({
    start: toLocalInputValue(request.eventStart),
    end: toLocalInputValue(request.eventEnd),
  }))
  const periodValid = period.start !== '' && period.end !== '' && period.end > period.start
  const fulfilment = useFulfilment(request, period, periodValid, reloadKey)
  const data = fulfilment.data
  const rows = useMemo(() => (data && periodValid ? buildRows({
    lines: data.lines,
    availability: data.availability,
    eventReservations: data.reservations,
    units: data.units,
    kindReservations: data.kindReservations,
    eventId: request.eventId,
    eventStart: request.eventStart,
    eventEnd: request.eventEnd,
    window: { from: toIso(period.start), to: toIso(period.end) },
  }) : null), [data, periodValid, period, request.eventId, request.eventStart, request.eventEnd])
  const reload = () => setReloadKey((n) => n + 1)

  return (
    <section className="page request-page">
      <Link className="request-page__back" to={`${TS}/requests`}><span aria-hidden="true">‹ </span>All requests</Link>
      <header className="card tech-card request-head">
        <div className="request-head__title">
          <p className="field-hint">Equipment request</p>
          <h1>{request.eventName}</h1>
          <span className="request-head__badges"><RequestBadges request={request} /></span>
        </div>
        <dl className="request-head__facts">
          <div><dt>When</dt><dd>{describeWhen(request.eventStart, request.eventEnd)}</dd></div>
          <div><dt>Organisation</dt><dd>{request.organisationName ?? 'Not recorded'}</dd></div>
          <div><dt>Requested by</dt><dd>{requesterName(request.requestedBy)}</dd></div>
        </dl>
        {request.technicalRequirement ? (
          <p className="request-head__note"><span>Technical note</span>{request.technicalRequirement}</p>
        ) : null}
      </header>

      <ReserveCard
        request={request}
        rows={rows}
        lines={data?.lines ?? []}
        reservations={data?.reservations ?? []}
        loaded={fulfilment.loaded}
        loadFailed={fulfilment.error}
        onRetry={fulfilment.retry}
        period={period}
        onPeriodChange={setPeriod}
        periodValid={periodValid}
        onRefresh={reload}
      />
      <DecisionCard
        request={request}
        lines={data?.lines ?? []}
        reservations={data?.reservations ?? []}
        ready={data !== null && (fulfilment.loaded || !periodValid)}
        rejectReason={rejectReason}
        onDecided={(decision) => {
          onDecided(decision.status, decision.rejectReason)
          reload()
        }}
      />
    </section>
  )
}
