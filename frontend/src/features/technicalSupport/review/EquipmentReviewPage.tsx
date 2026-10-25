import { useEffect, useState } from 'react'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { fetchProcessingRequests } from '../reservations/reservationApi'
import type { EquipmentRequestSummary } from '../reservations/reservation.types'
import { formatMoment } from '../reservations/reservationUtils'
import { RequestAvailabilitySection } from './RequestAvailabilitySection'
import '../technicalSupport.css'

export function EquipmentReviewPage() {
  const [requests, setRequests] = useState<EquipmentRequestSummary[]>([])
  const [loadingRequests, setLoadingRequests] = useState(true)
  const [selectedRequest, setSelectedRequest] = useState<EquipmentRequestSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchProcessingRequests()
      .then((data) => { if (!cancelled) setRequests(data) })
      .catch(() => { if (!cancelled) setError('Could not load events with equipment requests.') })
      .finally(() => { if (!cancelled) setLoadingRequests(false) })
    return () => { cancelled = true }
  }, [])

  return (
    <section className="page">
      <PageHeader
        title="Equipment request review"
        description="Read-only check of requested equipment against stock and unit status during the event’s scheduled period."
      />

      {error && <Notice tone="danger">{error}</Notice>}

      <Card className="tech-card">
        <h2>Events with equipment requests</h2>
        {loadingRequests && <p>Loading events…</p>}
        {!loadingRequests && requests.length === 0 && <p>No equipment requests are awaiting review.</p>}
        <ul className="tech-choice-list">
          {requests.map((request) => (
            <li key={request.requestId}>
              <button
                type="button"
                className="tech-choice"
                onClick={() => setSelectedRequest(request)}
                aria-pressed={selectedRequest?.requestId === request.requestId}
              >
                {request.eventName} ({formatMoment(request.eventStart)} – {formatMoment(request.eventEnd)})
              </button>
            </li>
          ))}
        </ul>
      </Card>

      {selectedRequest && (
        <RequestAvailabilitySection key={selectedRequest.requestId} request={selectedRequest} />
      )}
    </section>
  )
}
