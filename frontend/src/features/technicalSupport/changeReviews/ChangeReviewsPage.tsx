import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../../components/ui/Button'
import { Card } from '../../../components/ui/Card'
import { Notice } from '../../../components/ui/Notice'
import { PageHeader } from '../../../components/ui/PageHeader'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { fetchChangeReviews } from './changeReviewApi'
import { OUTCOME_LABELS, type ChangeReviewSummary } from './changeReview.types'
import '../technicalSupport.css'
import './changeReviews.css'

type State =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'loaded'; reviews: ChangeReviewSummary[] }

/** TS11: approved event changes that affect equipment; those still needing review come first. */
export function ChangeReviewsPage() {
  const [state, setState] = useState<State>({ status: 'loading' })

  const load = useCallback(() => {
    fetchChangeReviews()
      .then((reviews) => setState({ status: 'loaded', reviews }))
      .catch(() => setState({ status: 'error' }))
  }, [])

  useEffect(() => { load() }, [load])

  function retry() {
    setState({ status: 'loading' })
    load()
  }

  return (
    <section className="page">
      <PageHeader
        title="Equipment change reviews"
        description="Approved event changes that affect equipment. Check each one against the event's reservations."
      />
      <Card className="tech-card">
        {state.status === 'loading' && <p>Loading changes…</p>}
        {state.status === 'error' && (
          <>
            <Notice tone="danger">Could not load the equipment change reviews.</Notice>
            <Button variant="secondary" onClick={retry}>Try again</Button>
          </>
        )}
        {state.status === 'loaded' && state.reviews.length === 0 && (
          <p>No approved changes have affected equipment yet.</p>
        )}
        {state.status === 'loaded' && state.reviews.length > 0 && (
          <ul className="tech-choice-list change-review-list">
            {state.reviews.map((review) => (
              <li key={review.reviewId}>
                <Link to={`/technical-support/change-reviews/${review.reviewId}`} className="change-review-list__link">
                  <span className="change-review-list__name">{review.eventName}</span>
                  <span className="change-review-list__meta">Changed {formatEventDateTime(review.changedAt)}</span>
                  <span className={review.needsReview ? 'chip change-review-chip--open' : 'chip'}>
                    {review.needsReview ? 'Needs review' : OUTCOME_LABELS[review.outcome ?? 'still_suitable']}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </section>
  )
}
