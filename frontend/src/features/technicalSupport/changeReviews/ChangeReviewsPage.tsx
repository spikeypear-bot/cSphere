import { PageHeader } from '../../../components/ui/PageHeader'
import { formatEventDateTime } from '../../venueStaff/formatEventDateTime'
import { TS } from '../shared/faultyEquipment'
import { ResourceView } from '../../../components/ui/ResourceView'
import { RowLink } from '../../../components/ui/RowLink'
import { useResource } from '../../../lib/useResource'
import { fetchChangeReviews } from './changeReviewApi'
import { OUTCOME_LABELS, type ChangeReviewSummary } from './changeReview.types'
import '../technicalSupport.css'

export function ReviewBadge({ review }: { review: Pick<ChangeReviewSummary, 'needsReview' | 'outcome'> }) {
  if (review.needsReview) return <span className="tech-badge tech-badge--warning">Needs review</span>
  return (
    <span className={`tech-badge ${review.outcome === 'needs_coordinator_action' ? 'tech-badge--danger' : 'tech-badge--success'}`}>
      {OUTCOME_LABELS[review.outcome ?? 'still_suitable']}
    </span>
  )
}

/** TS11: approved event changes that affect equipment; those still needing review come first. */
export function ChangeReviewsPage() {
  const reviews = useResource(fetchChangeReviews, 'change-reviews')

  return (
    <section className="page">
      <PageHeader
        title="Change reviews"
        description="Approved event changes that affect equipment. Check each one against the event's reservations."
      />
      <ResourceView resource={reviews} loadingText="Loading change reviews…"
        errorText="The change reviews could not be loaded."
        emptyText="No approved changes have affected equipment yet."
        isEmpty={(list) => list.length === 0}>
        {(list) => (
          <ul className="plain-list" aria-label="Change reviews">
            {list.map((review) => (
              <li key={review.reviewId}>
                <RowLink to={`${TS}/change-reviews/${review.reviewId}`} className="card tech-row-card" titleAs="h2"
                  title={review.eventName} badges={<ReviewBadge review={review} />}>
                  <p>Change approved {formatEventDateTime(review.changedAt)}</p>
                </RowLink>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </section>
  )
}
