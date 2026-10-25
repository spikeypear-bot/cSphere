import { useEffect, useState } from 'react'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import '../technicalSupport.css'
import type { EquipmentRequestSummary } from './reservation.types'
import { fetchProcessingRequests } from './reservationApi'
import { formatMoment } from './reservationUtils'
import { DecisionSection } from './DecisionSection'
import { ReservationsSection } from './ReservationsSection'
import { ReserveSection } from './ReserveSection'
import { useRequestPlanning } from './useRequestPlanning'

export function EquipmentReservationPage() {
  const [requests, setRequests] = useState<EquipmentRequestSummary[]>([])
  const [loadingRequests, setLoadingRequests] = useState(true)
  const [selectedRequest, setSelectedRequest] = useState<EquipmentRequestSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchProcessingRequests()
      .then(setRequests)
      .catch(() => setError('Could not load events needing equipment.'))
      .finally(() => setLoadingRequests(false))
  }, [])

  return (
    <section className="page">
      <PageHeader title="Equipment Reservations" />

      <Card className="tech-card">
        <h2>Events needing equipment</h2>
        {loadingRequests && <p>Loading events…</p>}
        <ul className="tech-choice-list">
          {requests.map((req) => (
            <li key={req.requestId}>
              <button
                type="button"
                className="tech-choice"
                onClick={() => setSelectedRequest(req)}
                aria-pressed={selectedRequest?.requestId === req.requestId}
              >
                {req.eventName} ({formatMoment(req.eventStart)} – {formatMoment(req.eventEnd)}) —{' '}
                {req.technicalRequirement}
              </button>
            </li>
          ))}
        </ul>
      </Card>

      {selectedRequest && (
        <SelectedRequest
          key={selectedRequest.requestId}
          request={selectedRequest}
          onDecided={(status) => {
            setSelectedRequest({ ...selectedRequest, status })
            setRequests((current) => current.filter((r) => r.requestId !== selectedRequest.requestId))
          }}
        />
      )}

      {error && <Notice tone="danger">{error}</Notice>}
    </section>
  )
}

function SelectedRequest({ request, onDecided }: {
  request: EquipmentRequestSummary
  onDecided: (status: EquipmentRequestSummary['status']) => void
}) {
  const [reloadCount, setReloadCount] = useState(0)
  const planning = useRequestPlanning(request, reloadCount)
  return (
    <>
      <Card className="tech-card">
        <h2>{request.eventName}</h2>
        <p>{request.technicalRequirement}</p>
        <h2>Requirements</h2>
        <ul>
          {planning.lines.map((line) => (
            <li key={line.equipmentId}>{line.equipmentName}: {line.quantity} needed</li>
          ))}
        </ul>
      </Card>
      {planning.error && <Notice tone="danger">{planning.error}</Notice>}
      <ReserveSection request={request} reloadKey={reloadCount} onReserved={() => setReloadCount((n) => n + 1)} />
      <ReservationsSection reservations={planning.reservations} />
      <DecisionSection
        request={request}
        lines={planning.lines}
        reservations={planning.reservations}
        ready={planning.loaded}
        onDecided={(updated) => {
          onDecided(updated.status)
          setReloadCount((n) => n + 1)
        }}
      />
    </>
  )
}
