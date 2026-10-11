import { useEffect, useState } from 'react'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { fetchAvailability, fetchProcessingRequests, fetchRequestLines } from '../reservations/reservationApi'
import type { EquipmentRequestSummary, TimePeriod } from '../reservations/reservation.types'
import { formatMoment, toLocalInputValue } from '../reservations/reservationUtils'
import { fetchEquipmentUnits } from '../status/equipmentStatusApi'
import type { EquipmentUnit } from '../status/equipmentStatus.types'
import { assessEquipmentReview } from './equipmentReviewUtils'
import './equipmentReview.css'
import '../technicalSupport.css'

interface ReviewData {
  period: TimePeriod
  lines: Awaited<ReturnType<typeof fetchRequestLines>>
  availability: Awaited<ReturnType<typeof fetchAvailability>>
  units: EquipmentUnit[]
}

type ReviewState =
  | { requestId: string; status: 'loading' }
  | { requestId: string; status: 'error' }
  | { requestId: string; status: 'loaded'; data: ReviewData }

export function EquipmentReviewPage() {
  const [requests, setRequests] = useState<EquipmentRequestSummary[]>([])
  const [loadingRequests, setLoadingRequests] = useState(true)
  const [selectedRequest, setSelectedRequest] = useState<EquipmentRequestSummary | null>(null)
  const [reviewState, setReviewState] = useState<ReviewState | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchProcessingRequests()
      .then((data) => { if (!cancelled) setRequests(data) })
      .catch(() => { if (!cancelled) setError('Could not load events with equipment requests.') })
      .finally(() => { if (!cancelled) setLoadingRequests(false) })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!selectedRequest) return

    const period: TimePeriod = {
      start: toLocalInputValue(selectedRequest.eventStart),
      end: toLocalInputValue(selectedRequest.eventEnd),
    }
    let cancelled = false
    Promise.all([
      fetchRequestLines(selectedRequest.requestId),
      fetchAvailability(selectedRequest.requestId, period),
      fetchEquipmentUnits(period),
    ])
      .then(([lines, availability, units]) => {
        if (!cancelled) {
          setReviewState({
            requestId: selectedRequest.requestId,
            status: 'loaded',
            data: { period, lines, availability, units },
          })
        }
      })
      .catch(() => {
        if (!cancelled) setReviewState({ requestId: selectedRequest.requestId, status: 'error' })
      })
    return () => { cancelled = true }
  }, [selectedRequest])

  const review = selectedRequest && reviewState?.requestId === selectedRequest.requestId &&
      reviewState.status === 'loaded'
    ? reviewState.data
    : null
  const loadingReview = selectedRequest !== null &&
    reviewState?.requestId === selectedRequest.requestId &&
    reviewState.status === 'loading'
  const reviewFailed = selectedRequest !== null &&
    reviewState?.requestId === selectedRequest.requestId &&
    reviewState.status === 'error'
  const results = review
    ? assessEquipmentReview(review.lines, review.availability, review.units)
    : []
  const canFulfill = results.length > 0 && results.every((item) => item.canFulfill)

  function selectRequest(request: EquipmentRequestSummary) {
    if (selectedRequest?.requestId === request.requestId) return
    setSelectedRequest(request)
    setReviewState({ requestId: request.requestId, status: 'loading' })
    setError(null)
  }

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
                onClick={() => selectRequest(request)}
                aria-pressed={selectedRequest?.requestId === request.requestId}
              >
                {request.eventName} ({formatMoment(request.eventStart)} – {formatMoment(request.eventEnd)})
              </button>
            </li>
          ))}
        </ul>
      </Card>

      {selectedRequest && (
        <Card className="tech-card">
          <h2>{selectedRequest.eventName}</h2>
          <p>
            Event period: {formatMoment(selectedRequest.eventStart)} – {formatMoment(selectedRequest.eventEnd)}
          </p>
          {selectedRequest.technicalRequirement && <p>{selectedRequest.technicalRequirement}</p>}
          {loadingReview && <p>Loading requirements, availability, and unit status…</p>}
          {reviewFailed && (
            <Notice tone="danger">Could not load the equipment review. Please try again.</Notice>
          )}
          {review && (
            <>
              {review.lines.length === 0 ? (
                <Notice tone="info">
                  This request has no itemised equipment quantities to compare. Review its technical notes separately.
                </Notice>
              ) : (
                <>
                  <Notice tone={canFulfill ? 'success' : 'danger'}>
                    {canFulfill
                      ? 'This equipment request can be fulfilled for the event period.'
                      : 'This equipment request cannot be fully fulfilled as-is.'}
                  </Notice>
                  <div className="equipment-review__items">
                    {results.map((result) => (
                      <section className="equipment-review__item" key={result.line.equipmentId}>
                        <h3>{result.line.equipmentName}</h3>
                        <dl>
                          <div>
                            <dt>Requested</dt>
                            <dd>{result.line.quantity}</dd>
                          </div>
                          <div>
                            <dt>Available for this period</dt>
                            <dd>
                              {result.availability
                                ? `${result.availability.availableQuantity} of ${result.availability.totalQuantity}`
                                : 'Could not be determined'}
                            </dd>
                          </div>
                          {result.availability?.serialised && (
                            <div>
                              <dt>Suitable units (status Available)</dt>
                              <dd>{result.suitableQuantity}</dd>
                            </div>
                          )}
                        </dl>
                        {!result.availability && (
                          <p role="alert" className="equipment-review__flag">
                            Availability could not be determined for this equipment type.
                          </p>
                        )}
                        {result.availability && !result.sufficient && (
                          <p role="alert" className="equipment-review__flag">
                            Insufficient quantity: {result.line.quantity} requested, but only{' '}
                            {result.availability.availableQuantity} available for this period.
                          </p>
                        )}
                        {result.availability?.serialised && !result.suitable && (
                          <p role="alert" className="equipment-review__flag">
                            {result.suitableQuantity === 0
                              ? 'No suitable units are available; all units are Faulty or Unavailable for this period.'
                              : `Only ${result.suitableQuantity} suitable units have status Available; ${result.line.quantity} requested.`}
                          </p>
                        )}
                        <h4>Unit status for this period</h4>
                        {result.availability?.serialised ? (
                          result.units.length > 0 ? (
                            <ul className="equipment-review__units">
                              {result.units.map((unit) => (
                                <li key={unit.serialNumber}>
                                  <span>{unit.serialNumber}</span>
                                  <span className={`equipment-review__status equipment-review__status--${unit.status.toLowerCase()}`}>
                                    {unit.status}
                                  </span>
                                </li>
                              ))}
                            </ul>
                          ) : (
                            <p>No serialised units are recorded for this equipment type.</p>
                          )
                        ) : (
                          <p>Individual unit statuses are not tracked for this quantity-based equipment.</p>
                        )}
                      </section>
                    ))}
                  </div>
                </>
              )}
            </>
          )}
        </Card>
      )}
    </section>
  )
}
