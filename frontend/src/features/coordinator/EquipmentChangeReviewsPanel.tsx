import { useCallback, useEffect, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { fetchEventChangeReviews } from '../technicalSupport/changeReviews/changeReviewApi'
import { OUTCOME_LABELS, type ChangeReviewSummary } from '../technicalSupport/changeReviews/changeReview.types'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'

type State =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'loaded'; reviews: ChangeReviewSummary[] }

/** TS11 AC12: Technical Support's review of each approved change that
 * affected this event's equipment, as the assigned coordinator sees it. */
export function EquipmentChangeReviewsPanel({ eventId }: { eventId: string }) {
  const [state, setState] = useState<State>({ status: 'loading' })

  const load = useCallback(() => {
    fetchEventChangeReviews(eventId)
      .then((reviews) => setState({ status: 'loaded', reviews }))
      .catch(() => setState({ status: 'error' }))
  }, [eventId])

  useEffect(() => { load() }, [load])

  if (state.status === 'loaded' && state.reviews.length === 0) return null

  return (
    <Card className="equipment-request__existing">
      <h2>Technical Support review of changes</h2>
      {state.status === 'loading' && <p>Loading Technical Support reviews…</p>}
      {state.status === 'error' && (
        <>
          <p>Could not load Technical Support's reviews of this event's changes.</p>
          <Button variant="secondary" onClick={() => { setState({ status: 'loading' }); load() }}>Try again</Button>
        </>
      )}
      {state.status === 'loaded' && (
        <ul>
          {state.reviews.map((review) => (
            <li key={review.reviewId}>
              Change approved {formatEventDateTime(review.changedAt)}:{' '}
              {review.needsReview ? (
                <strong>Awaiting Technical Support review</strong>
              ) : (
                <>
                  <strong>{OUTCOME_LABELS[review.outcome ?? 'still_suitable']}</strong>
                  {review.outcomeNote ? ` — ${review.outcomeNote}` : ''}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
