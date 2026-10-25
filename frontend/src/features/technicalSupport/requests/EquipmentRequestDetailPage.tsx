import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { DecisionSection } from '../reservations/DecisionSection'
import { ReservationsSection } from '../reservations/ReservationsSection'
import { ReserveSection } from '../reservations/ReserveSection'
import type { EquipmentRequestStatus, EquipmentRequestSummary } from '../reservations/reservation.types'
import { fetchEquipmentRequests } from '../reservations/reservationApi'
import { useRequestPlanning } from '../reservations/useRequestPlanning'
import { RequestAvailabilitySection } from '../review/RequestAvailabilitySection'
import { TS } from '../shared/faultyEquipment'
import { ResourceView } from '../shared/ResourceView'
import { useResource } from '../shared/useResource'
import { RequestBadges, RequestFacts } from './RequestFacts'
import '../technicalSupport.css'

/** One equipment request, start to finish on one page: TS01's availability
 * check, TS02's reserve form and saved reservations, and TS06's decision. */
export function EquipmentRequestDetailPage() {
  const { requestId = '' } = useParams<{ requestId: string }>()
  // There is no single-request read: the "all" list is the same shape and
  // already scoped to Technical Support (D32).
  const requests = useResource(() => fetchEquipmentRequests('all'), requestId)
  const [decided, setDecided] = useState<{ requestId: string; status: EquipmentRequestStatus } | null>(null)

  return (
    <ResourceView resource={requests} loadingText="Loading equipment request…"
      errorText="The equipment request could not be loaded."
      emptyText="This equipment request could not be found. It may belong to an event that was cancelled or has finished."
      isEmpty={(list) => !list.some((r) => r.requestId === requestId)}>
      {(list) => {
        const found = list.find((r) => r.requestId === requestId)!
        const request = decided?.requestId === requestId ? { ...found, status: decided.status } : found
        return (
          <RequestWorkspace
            key={request.requestId}
            request={request}
            onDecided={(status) => setDecided({ requestId, status })}
          />
        )
      }}
    </ResourceView>
  )
}

function RequestWorkspace({ request, onDecided }: {
  request: EquipmentRequestSummary
  onDecided: (status: EquipmentRequestStatus) => void
}) {
  // Bumped after a reservation or a decision: every section reloads.
  const [reloadKey, setReloadKey] = useState(0)
  const planning = useRequestPlanning(request, reloadKey)
  const reload = () => setReloadKey((n) => n + 1)

  return (
    <section className="page">
      <PageHeader
        eyebrow="Equipment request"
        title={request.eventName}
        actions={<Link className="button button--secondary" to={`${TS}/requests`}>All requests</Link>}
      />
      <Card className="tech-card">
        <div className="tech-heading-row">
          <h2>Request</h2>
          <RequestBadges request={request} />
        </div>
        <RequestFacts request={request} />
        {request.technicalRequirement ? <p>{request.technicalRequirement}</p> : null}
        <h3>Requirements</h3>
        <ul>
          {planning.lines.map((line) => (
            <li key={line.equipmentId}>{line.equipmentName}: {line.quantity} needed</li>
          ))}
        </ul>
      </Card>
      {planning.error && <Notice tone="danger">{planning.error}</Notice>}
      <RequestAvailabilitySection request={request} reloadKey={reloadKey} />
      <ReserveSection request={request} reloadKey={reloadKey} onReserved={reload} />
      <ReservationsSection reservations={planning.reservations} />
      <DecisionSection
        request={request}
        lines={planning.lines}
        reservations={planning.reservations}
        ready={planning.loaded}
        onDecided={(updated) => {
          onDecided(updated.status)
          reload()
        }}
      />
    </section>
  )
}
