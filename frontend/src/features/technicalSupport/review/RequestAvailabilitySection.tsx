import { useEffect, useState } from 'react'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { fetchAvailability, fetchRequestLines } from '../reservations/reservationApi'
import type { EquipmentRequestSummary, TimePeriod } from '../reservations/reservation.types'
import { toLocalInputValue } from '../reservations/reservationUtils'
import { fetchEquipmentUnits } from '../status/equipmentStatusApi'
import type { EquipmentUnit } from '../status/equipmentStatus.types'
import { assessEquipmentReview } from './equipmentReviewUtils'
import './equipmentReview.css'
import '../technicalSupport.css'

interface ReviewData {
  lines: Awaited<ReturnType<typeof fetchRequestLines>>
  availability: Awaited<ReturnType<typeof fetchAvailability>>
  units: EquipmentUnit[]
}

type ReviewState =
  | { key: string; status: 'error' }
  | { key: string; status: 'loaded'; data: ReviewData }

/** TS01: read-only check of one request's items against stock and unit
 * status during the event's scheduled period. `reloadKey` changes when
 * something on the page (a reservation, a decision) may have changed them. */
export function RequestAvailabilitySection({ request, reloadKey = 0 }: {
  request: EquipmentRequestSummary
  reloadKey?: number
}) {
  const [reviewState, setReviewState] = useState<ReviewState | null>(null)
  const key = `${request.requestId}#${reloadKey}`

  useEffect(() => {
    const period: TimePeriod = {
      start: toLocalInputValue(request.eventStart),
      end: toLocalInputValue(request.eventEnd),
    }
    let cancelled = false
    Promise.all([
      fetchRequestLines(request.requestId),
      fetchAvailability(request.requestId, period),
      fetchEquipmentUnits(period),
    ])
      .then(([lines, availability, units]) => {
        if (!cancelled) setReviewState({ key, status: 'loaded', data: { lines, availability, units } })
      })
      .catch(() => {
        if (!cancelled) setReviewState({ key, status: 'error' })
      })
    return () => { cancelled = true }
  }, [key, request.requestId, request.eventStart, request.eventEnd])

  const current = reviewState?.key === key ? reviewState : null
  const review = current?.status === 'loaded' ? current.data : null
  const results = review
    ? assessEquipmentReview(review.lines, review.availability, review.units)
    : []
  const canFulfill = results.length > 0 && results.every((item) => item.canFulfill)

  return (
    <Card className="tech-card">
      <h2>Availability for the event period</h2>
      {!current && <p>Loading requirements, availability, and unit status…</p>}
      {current?.status === 'error' && (
        <Notice tone="danger">Could not load the equipment review. Please try again.</Notice>
      )}
      {review && (
        review.lines.length === 0 ? (
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
        )
      )}
    </Card>
  )
}
